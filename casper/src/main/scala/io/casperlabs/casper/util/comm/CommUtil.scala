package io.casperlabs.casper.util.comm

import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import com.google.protobuf.ByteString
import cats.Monad
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.LastApprovedBlock.LastApprovedBlock
import io.casperlabs.casper._
import io.casperlabs.casper.protocol._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.protocol.routing.Packet
import io.casperlabs.comm.rp.Connect.RPConfAsk
import io.casperlabs.comm.rp._
import io.casperlabs.comm.rp.ProtocolHelper.{packet, toPacket}
import io.casperlabs.comm.transport.{Blob, PacketType, TransportLayer}
import io.casperlabs.comm.transport
import io.casperlabs.comm.rp.ProtocolHelper
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.effects._
import io.casperlabs.shared._

import scala.concurrent.duration._

object CommUtil {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  def sendBlock[F[_]: Monad: ConnectionsCell: TransportLayer: Log: Time: ErrorHandler: RPConfAsk](
      b: BlockMessage
  ): F[Unit] = {
    val serializedBlock = b.toByteString
    for {
      _ <- streamToPeers[F](transport.BlockMessage, serializedBlock)
      _ <- Log[F].info(s"Sent ${PrettyPrinter.buildString(b)} to peers")
    } yield ()
  }

  def sendBlockRequest[F[_]: Monad: ConnectionsCell: TransportLayer: Log: Time: ErrorHandler: RPConfAsk](
      r: BlockRequest
  ): F[Unit] = {
    val serialized = r.toByteString
    val hashString = PrettyPrinter.buildString(r.hash)
    for {
      _ <- sendToPeers[F](transport.BlockRequest, serialized)
      _ <- Log[F].info(s"Requested missing block $hashString from peers")
    } yield ()
  }

  def sendForkChoiceTipRequest[F[_]: Monad: ConnectionsCell: TransportLayer: Log: Time: RPConfAsk]
      : F[Unit] = {
    val serialized = ForkChoiceTipRequest().toByteString
    for {
      _ <- sendToPeers[F](transport.ForkChoiceTipRequest, serialized)
      _ <- Log[F].info(s"Requested fork tip from peers")
    } yield ()
  }

  def sendToPeers[F[_]: Monad: ConnectionsCell: TransportLayer: Log: Time: RPConfAsk](
      pType: PacketType,
      serializedMessage: ByteString
  ): F[Unit] =
    for {
      peers <- ConnectionsCell[F].read
      local <- RPConfAsk[F].reader(_.local)
      msg   = packet(local, pType, serializedMessage)
      _     <- TransportLayer[F].broadcast(peers, msg)
    } yield ()

  def streamToPeers[F[_]: Monad: ConnectionsCell: TransportLayer: Log: Time: RPConfAsk](
      pType: PacketType,
      serializedMessage: ByteString
  ): F[Unit] =
    for {
      peers <- ConnectionsCell[F].read
      local <- RPConfAsk[F].reader(_.local)
      msg   = Blob(local, Packet(pType.id, serializedMessage))
      _     <- TransportLayer[F].stream(peers, msg)
    } yield ()

  def requestApprovedBlock[F[_]: Monad: LastApprovedBlock: Log: Time: Metrics: TransportLayer: ConnectionsCell: ErrorHandler: PacketHandler: RPConfAsk]()
      : F[Unit] = {
    val request = ApprovedBlockRequest("PleaseSendMeAnApprovedBlock").toByteString
    for {
      maybeBootstrap <- RPConfAsk[F].reader(_.bootstraps.headOption)
      local          <- RPConfAsk[F].reader(_.local)
      _ <- maybeBootstrap match {
            case Some(bootstrap) =>
              val msg = packet(local, transport.ApprovedBlockRequest, request)
              TransportLayer[F].send(bootstrap, msg)
            case None => Log[F].warn("Cannot request for an approved block as standalone")
          }
    } yield ()
  }
}
