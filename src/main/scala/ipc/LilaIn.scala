package lila.ws

package ipc

import chess.format.Uci
import chess.{ Centis, Color, MoveMetrics }
import play.api.libs.json.*

sealed trait LilaIn extends Matchable:
  def write: String
  def critical: Boolean = false // will be buffered and resent after lila reboots

object LilaIn:

  sealed trait Site extends LilaIn

  sealed trait Lobby extends LilaIn

  sealed trait Room      extends LilaIn
  sealed trait Simul     extends Room
  sealed trait Team      extends Room
  sealed trait Swiss     extends Room
  sealed trait Tour      extends Room
  sealed trait Study     extends Room
  sealed trait Round     extends Room
  sealed trait Challenge extends Room
  sealed trait Racer     extends Room

  sealed trait AnyRoom
      extends Simul
      with Team
      with Swiss
      with Tour
      with Study
      with Round
      with Challenge
      with Racer

  case class TellSri(sri: Sri, user: Option[UserId], payload: JsValue) extends Site with Lobby:
    def write = s"tell/sri $sri ${optional(user.map(_.userId))} ${Json.stringify(payload)}"

  case class TellUser(userId: UserId, payload: JsObject) extends Site:
    def write = s"tell/user $userId ${Json.stringify(payload)}"

  case class NotifiedBatch(userIds: Iterable[UserId]) extends Site:
    def write = s"notified/batch ${commas(userIds)}"

  case class Lags(value: Map[UserId, Int]) extends Site:
    def write = s"lags ${commas(value.map { case (user, lag) => s"$user:$lag" })}"

  case class ConnectUser(user: UserId, silently: Boolean) extends Site:
    def write = s"connect/user ${user.userId}"

  case class DisconnectUsers(userIds: Set[UserId]) extends Site:
    def write = s"disconnect/users ${commas(userIds)}"

  case object WsBoot extends Site:
    def write             = "boot"
    override def critical = true

  case class Ping(at: UptimeMillis) extends Site with Round:
    def write = s"ping ${at.millis}"

  type SriUserId = (Sri, Option[UserId])
  case class ConnectSris(sris: Iterable[SriUserId]) extends Lobby:
    private def render(su: SriUserId) = s"${su._1}${su._2.fold("")(" " + _)}"
    def write                         = s"connect/sris ${commas(sris map render)}"

  case class DisconnectSris(sris: Iterable[Sri]) extends Lobby:
    def write = s"disconnect/sris ${commas(sris)}"

  case class Counters(members: Int, rounds: Int) extends Lobby:
    def write = s"counters $members $rounds"

  case class KeepAlives(roomIds: Iterable[RoomId]) extends AnyRoom:
    def write = s"room/alives ${commas(roomIds)}"
  case class ChatSay(roomId: RoomId, userId: UserId, msg: String) extends AnyRoom:
    def write = s"chat/say $roomId $userId $msg"
  case class ChatTimeout(roomId: RoomId, userId: UserId, suspectId: UserId, reason: String, text: String)
      extends AnyRoom:
    def write = s"chat/timeout $roomId $userId $suspectId $reason $text"

  case class TellRoomSri(roomId: RoomId, tellSri: TellSri) extends Study with Round:
    import tellSri.*
    def write = s"tell/room/sri $roomId $sri ${optional(user.map(_.userId))} ${Json.stringify(payload)}"

  case class RoomSetVersions(versions: Iterable[(String, SocketVersion)]) extends AnyRoom:
    def write =
      s"room/versions ${commas(versions.map { case (r, v) =>
          s"$r:$v"
        })}"

  case class WaitingUsers(roomId: RoomId, waiting: Set[UserId]) extends Tour:
    def write = s"tour/waiting $roomId ${commas(waiting)}"

  case class RoundPlayerDo(fullId: Game.FullId, payload: JsValue) extends Round:
    def write = s"r/do $fullId ${Json.stringify(payload)}"

  case class RoundMove(fullId: Game.FullId, uci: Uci, blur: Boolean, lag: MoveMetrics) extends Round:
    private def centis(c: Option[Centis]) = optional(c.map(_.centis.toString))
    def write =
      s"r/move $fullId ${uci.uci} ${boolean(blur)} ${centis(lag.clientLag)} ${centis(lag.clientMoveTime)} ${centis(lag.frameLag)}"
    override def critical = true

  case class RoundBerserk(gameId: Game.Id, userId: UserId) extends Round:
    def write = s"r/berserk $gameId $userId"

  case class RoundHold(fullId: Game.FullId, ip: IpAddress, mean: Int, sd: Int) extends Round:
    def write = s"r/hold $fullId $ip $mean $sd"
  case class RoundSelfReport(
      fullId: Game.FullId,
      ip: IpAddress,
      user: Option[UserId],
      name: String
  ) extends Round:
    def write = s"r/report $fullId $ip ${optional(user.map(_.userId))} $name"

  case class RoundFlag(gameId: Game.Id, color: Color, playerId: Option[Game.PlayerId]) extends Round:
    def write = s"r/flag $gameId ${writeColor(color)} ${optional(playerId.map(_.value))}"

  case class RoundBye(fullId: Game.FullId) extends Round:
    def write = s"r/bye $fullId"

  case class PlayerChatSay(roomId: RoomId, userIdOrColor: Either[UserId, Color], msg: String) extends Round:
    def author = userIdOrColor.fold(identity, writeColor)
    def write  = s"chat/say $roomId $author $msg"
  case class WatcherChatSay(roomId: RoomId, userId: UserId, msg: String) extends Round:
    def write = s"chat/say/w $roomId $userId $msg"

  case class RoundOnlines(many: Iterable[RoundCrowd.Output]) extends Round:
    private def one(r: RoundCrowd.Output): String =
      if (r.isEmpty) r.room.roomId.roomId
      else s"${r.room.roomId}${boolean(r.players.white > 0)}${boolean(r.players.black > 0)}"
    def write = s"r/ons ${commas(many map one)}"

  case class RoundLatency(millis: Int) extends Round:
    def write = s"r/latency $millis"

  case class ChallengePings(ids: Iterable[RoomId]) extends Challenge:
    def write = s"challenge/pings ${commas(ids)}"

  case class RacerScore(raceId: Racer.RaceId, playerId: Racer.PlayerId, score: Int) extends Racer:
    def write = s"racer/score $raceId ${playerId.key} $score"

  case class RacerJoin(raceId: Racer.RaceId, playerId: Racer.PlayerId) extends Racer:
    def write = s"racer/join $raceId ${playerId.key}"

  case class RacerStart(raceId: Racer.RaceId, playerId: Racer.PlayerId) extends Racer:
    def write = s"racer/start $raceId ${playerId.key}"

  case class ReqResponse(reqId: Int, value: String) extends Study with Simul with Site:
    def write = s"req/response $reqId $value"

  private def commas(as: Iterable[Any]): String   = if (as.isEmpty) "-" else as mkString ","
  private def boolean(b: Boolean): String         = if (b) "+" else "-"
  private def optional(s: Option[String]): String = s getOrElse "-"
  private def writeColor(c: Color): String        = c.fold("w", "b")
