package lila.ws

import play.api.libs.json.*
import chess.format.{ Fen, Uci, UciCharPair }
import chess.opening.{ FullOpening, FullOpeningDB }
import chess.Pos
import chess.variant.{ Crazyhouse, Variant }
import com.typesafe.scalalogging.Logger
import cats.syntax.option.*

import ipc.*

object Chess:

  private val logger = Logger(getClass)

  def apply(req: ClientOut.AnaMove): ClientIn =
    Monitor.time(_.chessMoveTime) {
      try
        chess
          .Game(req.variant.some, Some(req.fen))(req.orig, req.dest, req.promotion)
          .toOption flatMap { case (game, move) =>
          game.pgnMoves.lastOption map { san =>
            makeNode(game, Uci.WithSan(Uci(move), san), req.path, req.chapterId)
          }
        } getOrElse ClientIn.StepFailure
      catch
        case e: java.lang.ArrayIndexOutOfBoundsException =>
          logger.warn(s"${req.fen} ${req.variant} ${req.orig}${req.dest}", e)
          ClientIn.StepFailure
    }

  def apply(req: ClientOut.AnaDrop): ClientIn =
    Monitor.time(_.chessMoveTime) {
      try
        chess.Game(req.variant.some, Some(req.fen)).drop(req.role, req.pos).toOption flatMap {
          case (game, drop) =>
            game.pgnMoves.lastOption map { san =>
              makeNode(game, Uci.WithSan(Uci(drop), san), req.path, req.chapterId)
            }
        } getOrElse ClientIn.StepFailure
      catch
        case e: java.lang.ArrayIndexOutOfBoundsException =>
          logger.warn(s"${req.fen} ${req.variant} ${req.role}@${req.pos}", e)
          ClientIn.StepFailure
    }

  def apply(req: ClientOut.AnaDests): ClientIn.Dests =
    Monitor.time(_.chessDestTime) {
      ClientIn.Dests(
        path = req.path,
        dests = {
          if (req.variant.standard && req.fen == chess.format.Forsyth.initial && req.path.value.isEmpty)
            initialDests
          else {
            val sit = chess.Game(req.variant.some, Some(req.fen)).situation
            if (sit.playable(false)) json.destString(sit.destinations) else ""
          }
        },
        opening = {
          if (Variant.openingSensibleVariants(req.variant)) FullOpeningDB findByFen req.fen
          else None
        },
        chapterId = req.chapterId
      )
    }

  def apply(req: ClientOut.Opening): Option[ClientIn.Opening] =
    if (Variant.openingSensibleVariants(req.variant))
      FullOpeningDB findByFen req.fen map {
        ClientIn.Opening(req.path, _)
      }
    else None

  private def makeNode(
      game: chess.Game,
      move: Uci.WithSan,
      path: Path,
      chapterId: Option[ChapterId]
  ): ClientIn.Node =
    val movable = game.situation playable false
    val fen     = chess.format.Forsyth >> game
    ClientIn.Node(
      path = path,
      id = UciCharPair(move.uci),
      ply = game.turns,
      move = move,
      fen = fen,
      check = game.situation.check,
      dests = if (movable) game.situation.destinations else Map.empty,
      opening =
        if (game.turns <= 30 && Variant.openingSensibleVariants(game.board.variant))
          FullOpeningDB findByFen fen
        else None,
      drops = if (movable) game.situation.drops else Some(Nil),
      crazyData = game.situation.board.crazyData,
      chapterId = chapterId
    )

  private val initialDests = "iqy muC gvx ltB bqs pxF jrz nvD ksA owE"

  object json:
    given Writes[Fen] with
      def writes(fen: Fen) = JsString(fen.value)
    given Writes[Path] with
      def writes(path: Path) = JsString(path.value)
    given Writes[Uci] with
      def writes(uci: Uci) = JsString(uci.uci)
    given Writes[UciCharPair] with
      def writes(ucp: UciCharPair) = JsString(ucp.toString)
    given Writes[Pos] with
      def writes(pos: Pos) = JsString(pos.key)
    given Writes[ChapterId] with
      def writes(ch: ChapterId) = JsString(ch.value)
    given Writes[FullOpening] with
      def writes(o: FullOpening) = Json.obj("eco" -> o.eco, "name" -> o.name)
    given Writes[Map[Pos, List[Pos]]] with
      def writes(dests: Map[Pos, List[Pos]]) = JsString(destString(dests))

    def destString(dests: Map[Pos, List[Pos]]): String =
      val sb    = new java.lang.StringBuilder(80)
      var first = true
      dests foreach { (orig, dests) =>
        if (first) first = false
        else sb append " "
        sb append orig.asChar
        dests foreach { sb append _.asChar }
      }
      sb.toString

    given OWrites[Crazyhouse.Pocket] = OWrites { v =>
      JsObject(
        Crazyhouse.storableRoles.flatMap { role =>
          Some(v.roles.count(role == _)).filter(0 < _).map { count => role.name -> JsNumber(count) }
        }
      )
    }
    given OWrites[chess.variant.Crazyhouse.Data] = OWrites { v =>
      Json.obj("pockets" -> List(v.pockets.white, v.pockets.black))
    }
