package sharry.store

import javax.sql.DataSource

import cats.data.OptionT
import cats.effect._
import cats.implicits._
import fs2.Chunk

import sharry.common._
import sharry.store.doobie.AttributeStore
import sharry.store.records.RFileMeta

import _root_.doobie._
import binny._

trait FileStore[F[_]] {

  def chunkSize: Int

  def delete(id: Ident): F[Unit]

  def findMeta(id: Ident): OptionT[F, RFileMeta]

  def findBinary(id: Ident, range: ByteRange): OptionT[F, Binary[F]]

  def insert(data: Binary[F], hint: Hint, created: Timestamp): F[RFileMeta]

  def insertMeta(meta: RFileMeta): F[Unit]

  def updateChecksum(meta: RFileMeta): F[Unit]

  def addChunk(id: Ident, hint: Hint, chunkDef: ChunkDef, data: Chunk[Byte]): F[Unit]

  def computeAttributes: ComputeChecksum[F]

  def createBinaryStore: FileStoreConfig => ChunkedBinaryStore[F]
}

object FileStore {

  def apply[F[_]: Async](
      ds: DataSource,
      xa: Transactor[F],
      chunkSize: Int,
      computeChecksumConfig: ComputeChecksumConfig,
      config: FileStoreConfig
  ): F[FileStore[F]] = {
    val create = FileStoreConfig.createBinaryStore[F](ds, chunkSize) _
    val bs = create(config)
    val as = AttributeStore(xa)
    ComputeChecksum[F](bs, computeChecksumConfig).map(cc =>
      new Impl[F](bs, as, chunkSize, cc, create)
    )
  }

  final private class Impl[F[_]: Sync](
      bs: ChunkedBinaryStore[F],
      attrStore: AttributeStore[F],
      val chunkSize: Int,
      val computeAttributes: ComputeChecksum[F],
      val createBinaryStore: FileStoreConfig => ChunkedBinaryStore[F]
  ) extends FileStore[F] {

    def delete(id: Ident): F[Unit] =
      bs.delete(BinaryId(id.id))

    def findMeta(id: Ident): OptionT[F, RFileMeta] =
      attrStore.findMeta(BinaryId(id.id))

    def findBinary(id: Ident, range: ByteRange): OptionT[F, Binary[F]] =
      bs.findBinary(BinaryId(id.id), range)

    def insert(data: Binary[F], hint: Hint, created: Timestamp): F[RFileMeta] =
      data
        .through(bs.insert)
        .evalMap { id =>
          computeAttributes.submit(id, hint) *>
            computeAttributes
              .computeSync(id, hint, AttributeName.excludeSha256)
              .flatTap(insertMeta)
        }
        .compile
        .lastOrError

    def insertMeta(meta: RFileMeta): F[Unit] =
      attrStore.saveMeta(meta)

    def updateChecksum(meta: RFileMeta): F[Unit] =
      attrStore.updateChecksum(meta.id, meta.checksum)

    def addChunk(
        id: Ident,
        hint: Hint,
        chunkDef: ChunkDef,
        data: Chunk[Byte]
    ): F[Unit] =
      bs.insertChunk(BinaryId(id.id), chunkDef, hint, data.toByteVector).flatMap {
        case InsertChunkResult.Complete =>
          computeAttributes.submit(BinaryId(id.id), hint) *>
            computeAttributes
              .computeSync(BinaryId(id.id), hint, AttributeName.excludeSha256)
              .flatMap(insertMeta)
        case InsertChunkResult.Incomplete => ().pure[F]
        case fail: InsertChunkResult.Failure =>
          Sync[F].raiseError(new Exception(s"Inserting chunk failed: $fail"))
      }
  }
}