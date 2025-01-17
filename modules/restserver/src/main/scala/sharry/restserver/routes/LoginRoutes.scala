package sharry.restserver.routes

import cats.data.OptionT
import cats.effect._
import cats.implicits._

import sharry.backend.BackendApp
import sharry.backend.account.NewAccount
import sharry.backend.auth._
import sharry.common._
import sharry.restapi.model._
import sharry.restserver._
import sharry.restserver.config.Config
import sharry.restserver.http4s.ClientRequestInfo
import sharry.restserver.oauth.CodeFlow

import org.http4s._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Location

object LoginRoutes {

  def login[F[_]: Async](
      S: BackendApp[F],
      client: Client[F],
      cfg: Config
  ): HttpRoutes[F] = {
    val logger = sharry.logging.getLogger[F]
    val dsl: Http4sDsl[F] = new Http4sDsl[F] {}
    import dsl._

    HttpRoutes.of[F] {
      case req @ POST -> Root / "login" =>
        for {
          up <- req.as[UserPass]
          res <- S.login.loginUserPass(cfg.backend.auth)(
            UserPassData(up.account, Password(up.password))
          )
          resp <- makeResponse(dsl, cfg, req, res, up.account)
        } yield resp

      case req @ GET -> Root / "oauth" / id =>
        findOAuthProvider(cfg, id) match {
          case Some(p) =>
            val uri = p.authorizeUrl
              .withQuery("client_id", p.clientId)
              .withQuery(
                "redirect_uri",
                redirectUri(cfg, req, p).asString
              )
              .withQuery("response_type", "code")
            logger.debug(s"Redirecting to OAuth provider ${p.id.id}: ${uri.asString}") *>
              SeeOther().map(_.withHeaders(Location(Uri.unsafeFromString(uri.asString))))
          case None =>
            logger.debug(s"No oauth provider found with id '$id'") *> BadRequest()
        }

      case req @ GET -> Root / "oauth" / id / "resume" =>
        val prov = OptionT.fromOption[F](findOAuthProvider(cfg, id))
        val code = OptionT.fromOption[F](req.params.get("code"))

        val userId = for {
          p <- prov
          c <- code
          u <- CodeFlow(client)(p, redirectUri(cfg, req, p).asString, c)
          newAcc <- OptionT.liftF(
            NewAccount.create(
              u.id ++ Ident.atSign ++ p.id,
              AccountSource.OAuth(p.id.id),
              email = u.email
            )
          )
          acc <- OptionT.liftF(S.account.createIfMissing(newAcc))
          accId = acc.accountId(None)
          _ <- OptionT.liftF(S.account.updateLoginStats(accId))
          token <- OptionT.liftF(
            AuthToken.user[F](accId, cfg.backend.auth.serverSecret)
          )
        } yield token

        val uri = getBaseUrl(cfg, req).withQuery("oauth", "1") / "app" / "login"
        val location = Location(Uri.unsafeFromString(uri.asString))
        userId.value.flatMap {
          case Some(t) =>
            TemporaryRedirect(location)
              .map(_.addCookie(CookieData(t).asCookie(getBaseUrl(cfg, req))))
          case None => TemporaryRedirect(location)
        }
    }
  }

  private def redirectUri[F[_]](
      cfg: Config,
      req: Request[F],
      prov: AuthConfig.OAuth
  ): LenientUri =
    getBaseUrl(
      cfg,
      req
    ) / "api" / "v2" / "open" / "auth" / "oauth" / prov.id.id / "resume"

  private def findOAuthProvider(cfg: Config, id: String): Option[AuthConfig.OAuth] =
    cfg.backend.auth.oauth.filter(_.enabled).find(_.id.id == id)

  def session[F[_]: Async](S: Login[F], cfg: Config): HttpRoutes[F] = {
    val dsl: Http4sDsl[F] = new Http4sDsl[F] {}
    import dsl._

    HttpRoutes.of[F] {
      case req @ POST -> Root / "session" =>
        Authenticate
          .authenticateRequest(S.loginSession(cfg.backend.auth))(req)
          .flatMap(res =>
            makeResponse(dsl, cfg, req, res, "unknown due to session login")
          )

      case req @ POST -> Root / "logout" =>
        Ok().map(_.addCookie(CookieData.deleteCookie(getBaseUrl(cfg, req))))
    }
  }

  private def getBaseUrl[F[_]](cfg: Config, req: Request[F]): LenientUri =
    ClientRequestInfo.getBaseUrl(cfg, req)

  def makeResponse[F[_]: Async](
      dsl: Http4sDsl[F],
      cfg: Config,
      req: Request[F],
      res: LoginResult,
      accountName: String
  ): F[Response[F]] = {
    import dsl._
    val logger = sharry.logging.getLogger[F]

    res match {
      case LoginResult.Ok(token) =>
        for {
          cd <-
            AuthToken
              .user(token.account, cfg.backend.auth.serverSecret)
              .map(CookieData.apply)
          resp <- Ok(
            AuthResult(
              token.account.id,
              token.account.userLogin,
              token.account.admin,
              true,
              "Login successful",
              Some(cd.asString),
              cfg.backend.auth.sessionValid.millis
            )
          ).map(_.addCookie(cd.asCookie(getBaseUrl(cfg, req))))
        } yield resp
      case _ =>
        logger.info(
          s"Authentication attempt failure for username $accountName from ip ${req.from.map(_.toInetAddress.getHostAddress).getOrElse("Unknown ip")}"
        ) *>
          Ok(
            AuthResult(Ident.empty, Ident.empty, false, false, "Login failed.", None, 0L)
          )
    }
  }

}
