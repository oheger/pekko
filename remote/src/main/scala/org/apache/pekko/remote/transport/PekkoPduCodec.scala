/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.remote.transport

import scala.annotation.nowarn
import org.apache.pekko
import pekko.PekkoException
import pekko.actor.{ ActorRef, Address, AddressFromURIString, InternalActorRef }
import pekko.protobufv3.internal.InvalidProtocolBufferException
import pekko.remote._
import pekko.remote.WireFormats._
import pekko.util.ByteString
import pekko.util.OptionVal

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[remote] class PduCodecException(msg: String, cause: Throwable) extends PekkoException(msg, cause)

/**
 * INTERNAL API
 *
 * Companion object of the [[pekko.remote.transport.PekkoPduCodec]] trait. Contains the representation case classes
 * of decoded Pekko Protocol Data Units (PDUs).
 */
@nowarn("msg=deprecated")
private[remote] object PekkoPduCodec {

  /**
   * Trait that represents decoded Pekko PDUs (Protocol Data Units)
   */
  sealed trait PekkoPdu
  final case class Associate(info: HandshakeInfo) extends PekkoPdu
  final case class Disassociate(reason: AssociationHandle.DisassociateInfo) extends PekkoPdu
  case object Heartbeat extends PekkoPdu
  final case class Payload(bytes: ByteString) extends PekkoPdu

  final case class Message(
      recipient: InternalActorRef,
      recipientAddress: Address,
      serializedMessage: SerializedMessage,
      senderOption: OptionVal[ActorRef],
      seqOption: Option[SeqNo])
      extends HasSequenceNumber {

    def reliableDeliveryEnabled = seqOption.isDefined

    override def seq: SeqNo = seqOption.get
  }
}

/**
 * INTERNAL API
 *
 * A Codec that is able to convert Pekko PDUs (Protocol Data Units) from and to [[pekko.util.ByteString]]s.
 */
@nowarn("msg=deprecated")
private[remote] trait PekkoPduCodec {
  import PekkoPduCodec._

  /**
   * Returns an [[pekko.remote.transport.PekkoPduCodec.PekkoPdu]] instance that represents the PDU contained in the raw
   * ByteString.
   *
   * @param raw
   *   Encoded raw byte representation of a Pekko PDU
   * @return
   *   Case class representation of the decoded PDU that can be used in a match statement
   */
  def decodePdu(raw: ByteString): PekkoPdu

  /**
   * Takes an [[pekko.remote.transport.PekkoPduCodec.PekkoPdu]] representation of a Pekko PDU and returns its encoded
   * form as a [[pekko.util.ByteString]].
   *
   * For the same effect the constructXXX methods might be called directly, taking method parameters instead of the
   * [[pekko.remote.transport.PekkoPduCodec.PekkoPdu]] final case classes.
   *
   * @param pdu
   *   The Pekko Protocol Data Unit to be encoded
   * @return
   *   Encoded form as raw bytes
   */
  def encodePdu(pdu: PekkoPdu): ByteString = pdu match {
    case Associate(info)      => constructAssociate(info)
    case Payload(bytes)       => constructPayload(bytes)
    case Disassociate(reason) => constructDisassociate(reason)
    case Heartbeat            => constructHeartbeat
  }

  def constructPayload(payload: ByteString): ByteString

  def constructAssociate(info: HandshakeInfo): ByteString

  def constructDisassociate(reason: AssociationHandle.DisassociateInfo): ByteString

  def constructHeartbeat: ByteString

  def decodeMessage(
      raw: ByteString,
      provider: RemoteActorRefProvider,
      localAddress: Address): (Option[Ack], Option[Message])

  def constructMessage(
      localAddress: Address,
      recipient: ActorRef,
      serializedMessage: SerializedMessage,
      senderOption: OptionVal[ActorRef],
      seqOption: Option[SeqNo] = None,
      ackOption: Option[Ack] = None): ByteString

  def constructPureAck(ack: Ack): ByteString
}

/**
 * INTERNAL API
 */
@nowarn("msg=deprecated")
private[remote] object PekkoPduProtobufCodec extends PekkoPduCodec {
  import PekkoPduCodec._

  private def ackBuilder(ack: Ack): AcknowledgementInfo.Builder = {
    val ackBuilder = AcknowledgementInfo.newBuilder()
    ackBuilder.setCumulativeAck(ack.cumulativeAck.rawValue)
    ack.nacks.foreach { nack =>
      ackBuilder.addNacks(nack.rawValue)
    }
    ackBuilder
  }

  override def constructMessage(
      localAddress: Address,
      recipient: ActorRef,
      serializedMessage: SerializedMessage,
      senderOption: OptionVal[ActorRef],
      seqOption: Option[SeqNo] = None,
      ackOption: Option[Ack] = None): ByteString = {

    val ackAndEnvelopeBuilder = AckAndEnvelopeContainer.newBuilder

    val envelopeBuilder = RemoteEnvelope.newBuilder

    envelopeBuilder.setRecipient(serializeActorRef(recipient.path.address, recipient))
    senderOption match {
      case OptionVal.Some(sender) => envelopeBuilder.setSender(serializeActorRef(localAddress, sender))
      case _                      =>
    }

    seqOption.foreach { seq =>
      envelopeBuilder.setSeq(seq.rawValue)
    }
    ackOption.foreach { ack =>
      ackAndEnvelopeBuilder.setAck(ackBuilder(ack))
    }
    envelopeBuilder.setMessage(serializedMessage)
    ackAndEnvelopeBuilder.setEnvelope(envelopeBuilder)

    ByteString.fromArrayUnsafe(ackAndEnvelopeBuilder.build.toByteArray)
  }

  override def constructPureAck(ack: Ack): ByteString =
    ByteString.fromArrayUnsafe(AckAndEnvelopeContainer.newBuilder.setAck(ackBuilder(ack)).build().toByteArray)

  override def constructPayload(payload: ByteString): ByteString =
    ByteString.fromArrayUnsafe(
      PekkoProtocolMessage.newBuilder().setPayload(ByteStringUtils.toProtoByteStringUnsafe(payload)).build.toByteArray)

  override def constructAssociate(info: HandshakeInfo): ByteString = {
    val handshakeInfo = PekkoHandshakeInfo.newBuilder.setOrigin(serializeAddress(info.origin)).setUid(info.uid.toLong)
    constructControlMessagePdu(WireFormats.CommandType.ASSOCIATE, Some(handshakeInfo))
  }

  private val DISASSOCIATE = constructControlMessagePdu(WireFormats.CommandType.DISASSOCIATE, None)
  private val DISASSOCIATE_SHUTTING_DOWN =
    constructControlMessagePdu(WireFormats.CommandType.DISASSOCIATE_SHUTTING_DOWN, None)
  private val DISASSOCIATE_QUARANTINED =
    constructControlMessagePdu(WireFormats.CommandType.DISASSOCIATE_QUARANTINED, None)

  override def constructDisassociate(info: AssociationHandle.DisassociateInfo): ByteString = info match {
    case AssociationHandle.Unknown     => DISASSOCIATE
    case AssociationHandle.Shutdown    => DISASSOCIATE_SHUTTING_DOWN
    case AssociationHandle.Quarantined => DISASSOCIATE_QUARANTINED
  }

  override val constructHeartbeat: ByteString =
    constructControlMessagePdu(WireFormats.CommandType.HEARTBEAT, None)

  override def decodePdu(raw: ByteString): PekkoPdu = {
    try {
      val pdu = PekkoProtocolMessage.parseFrom(raw.toArrayUnsafe())
      if (pdu.hasPayload) Payload(ByteString.fromByteBuffer(pdu.getPayload.asReadOnlyByteBuffer()))
      else if (pdu.hasInstruction) decodeControlPdu(pdu.getInstruction)
      else
        throw new PduCodecException("Error decoding Pekko PDU: Neither message nor control message were contained",
          null)
    } catch {
      case e: InvalidProtocolBufferException => throw new PduCodecException("Decoding PDU failed.", e)
    }
  }

  override def decodeMessage(
      raw: ByteString,
      provider: RemoteActorRefProvider,
      localAddress: Address): (Option[Ack], Option[Message]) = {
    val ackAndEnvelope = AckAndEnvelopeContainer.parseFrom(raw.toArrayUnsafe())

    val ackOption = if (ackAndEnvelope.hasAck) {
      import pekko.util.ccompat.JavaConverters._
      Some(
        Ack(
          SeqNo(ackAndEnvelope.getAck.getCumulativeAck),
          ackAndEnvelope.getAck.getNacksList.asScala.map(SeqNo(_)).toSet))
    } else None

    val messageOption = if (ackAndEnvelope.hasEnvelope) {
      val msgPdu = ackAndEnvelope.getEnvelope
      Some(
        Message(
          recipient = provider.resolveActorRefWithLocalAddress(msgPdu.getRecipient.getPath, localAddress),
          recipientAddress = AddressFromURIString(msgPdu.getRecipient.getPath),
          serializedMessage = msgPdu.getMessage,
          senderOption =
            if (msgPdu.hasSender)
              OptionVal(provider.resolveActorRefWithLocalAddress(msgPdu.getSender.getPath, localAddress))
            else OptionVal.None,
          seqOption = if (msgPdu.hasSeq) Some(SeqNo(msgPdu.getSeq)) else None))
    } else None

    (ackOption, messageOption)
  }

  private def decodeControlPdu(controlPdu: PekkoControlMessage): PekkoPdu = {

    controlPdu.getCommandType match {
      case CommandType.ASSOCIATE if controlPdu.hasHandshakeInfo =>
        val handshakeInfo = controlPdu.getHandshakeInfo
        Associate(
          HandshakeInfo(
            decodeAddress(handshakeInfo.getOrigin),
            handshakeInfo.getUid.toInt // 64 bits are allocated in the wire formats, but we use only 32 for now
          ))
      case CommandType.DISASSOCIATE               => Disassociate(AssociationHandle.Unknown)
      case CommandType.DISASSOCIATE_SHUTTING_DOWN => Disassociate(AssociationHandle.Shutdown)
      case CommandType.DISASSOCIATE_QUARANTINED   => Disassociate(AssociationHandle.Quarantined)
      case CommandType.HEARTBEAT                  => Heartbeat
      case x                                      =>
        throw new PduCodecException(s"Decoding of control PDU failed, invalid format, unexpected: [$x]", null)
    }
  }

  private def decodeAddress(encodedAddress: AddressData): Address =
    Address(encodedAddress.getProtocol, encodedAddress.getSystem, encodedAddress.getHostname, encodedAddress.getPort)

  private def constructControlMessagePdu(
      code: WireFormats.CommandType,
      handshakeInfo: Option[PekkoHandshakeInfo.Builder]): ByteString = {

    val controlMessageBuilder = PekkoControlMessage.newBuilder()
    controlMessageBuilder.setCommandType(code)
    handshakeInfo.foreach(controlMessageBuilder.setHandshakeInfo)

    ByteString.ByteString1C(
      PekkoProtocolMessage
        .newBuilder()
        .setInstruction(controlMessageBuilder.build)
        .build
        .toByteArray) // Reuse Byte Array (naughty!)
  }

  private def serializeActorRef(defaultAddress: Address, ref: ActorRef): ActorRefData = {
    ActorRefData.newBuilder
      .setPath(
        if (ref.path.address.host.isDefined) ref.path.toSerializationFormat
        else ref.path.toSerializationFormatWithAddress(defaultAddress))
      .build()
  }

  private def serializeAddress(address: Address): AddressData = address match {
    case Address(protocol, system, Some(host), Some(port)) =>
      AddressData.newBuilder.setHostname(host).setPort(port).setSystem(system).setProtocol(protocol).build()
    case _ => throw new IllegalArgumentException(s"Address [$address] could not be serialized: host or port missing.")
  }

}
