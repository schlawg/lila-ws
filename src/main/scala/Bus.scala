package lila.ws

import akka.actor.typed.ActorRef

import ipc.ClientMsg
import org.joda.time.LocalTime

object Bus:

  type Chan = String

  private val impl = new util.EventBus[ClientMsg, Chan, ActorRef[ClientMsg]](
    initialCapacity = 65535,
    publish = (actor, event) => {
      if (
        actor.toString != channel.mlat 
        && actor.toString != channel.all
        && !event.toString.startsWith("Broom"))
        s"Bus.impl.parse [$actor] $event".pp(s"${LocalTime.now()}")

      actor ! event
    }
  )

  def subscribe   = impl.subscribe
  def unsubscribe = impl.unsubscribe

  def publish(chan: Chan, event: ClientMsg): Unit =
    impl.publish(chan, event)

  def publish(chan: ChanSelect, event: ClientMsg): Unit =
    impl.publish(chan(channel), event)

  def publish(msg: Msg): Unit =
    impl.publish(msg.channel, msg.event)

  case class Msg(event: ClientMsg, channel: Chan)

  type ChanSelect = Bus.channel.type => Chan

  object channel:
    def sri(s: Sri)               = s"sri/$s"
    def flag(f: Flag)             = s"flag/$f"
    val mlat                      = "mlat"
    val all                       = "all"
    val lobby                     = "lobby"
    val tv                        = "tv"
    def userTv(u: UserTv)         = s"userTv/${u.tvUserId}"
    def room(id: RoomId)          = s"room/$id"
    def tourStanding(id: Tour.ID) = s"tour-standing/$id"
    def externalChat(id: RoomId)  = s"external-chat/$id"

  def msg(event: ClientMsg, chan: ChanSelect) =
    Msg(event, chan(channel))

  def size                     = impl.size
  def sizeOf(chan: ChanSelect) = impl sizeOf chan(channel)

  // distinct bus for internal events
  val internal = new util.EventBus[Matchable, Chan, PartialFunction[Matchable, Unit]](
    initialCapacity = 16,
    publish = (listener, event) => listener lift event
  )
