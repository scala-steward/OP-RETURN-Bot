package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import com.bot4s.telegram.api.RequestHandler
import com.bot4s.telegram.api.declarative.Commands
import com.bot4s.telegram.clients.FutureSttpClient
import com.bot4s.telegram.future.{Polling, TelegramBot}
import com.bot4s.telegram.methods.SetMyCommands
import com.bot4s.telegram.models.{BotCommand, Message}
import com.danielasfregola.twitter4s.entities.Tweet
import config.OpReturnBotAppConfig
import models.{InvoiceDAO, InvoiceDb}
import org.bitcoins.commons.jsonmodels.lnd.TxDetails
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.util.StartStopAsync
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.Sha256Digest
import sttp.capabilities.akka.AkkaStreams
import sttp.client3.SttpBackend
import sttp.client3.akkahttp.AkkaHttpBackend

import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale
import scala.concurrent.Future

class TelegramHandler(controller: Controller)(implicit
    config: OpReturnBotAppConfig,
    system: ActorSystem)
    extends TelegramBot
    with Polling
    with Commands[Future]
    with StartStopAsync[Unit] {

  private val intFormatter: NumberFormat =
    java.text.NumberFormat.getIntegerInstance

  private val currencyFormatter: NumberFormat =
    java.text.NumberFormat.getCurrencyInstance(Locale.US)

  val invoiceDAO: InvoiceDAO = InvoiceDAO()

  private val myTelegramId = config.telegramId
  private val telegramCreds = config.telegramCreds

  implicit private val backend: SttpBackend[Future, AkkaStreams] =
    AkkaHttpBackend.usingActorSystem(system)

  override val client: RequestHandler[Future] = new FutureSttpClient(
    telegramCreds)

  override def start(): Future[Unit] = {
    val commands = List(
      BotCommand("report", "Generate report of profit and total on chain fees"),
      BotCommand("processunhandled", "Forces processing of invoices"),
      BotCommand("create", "Creating an invoice with the given message")
    )

    for {
      _ <- run()
      _ <- request(SetMyCommands(commands))
      _ <- sendTelegramMessage("Connected!", myTelegramId)
    } yield ()
  }

  override def stop(): Future[Unit] = Future.unit

  private def checkAdminMessage(msg: Message): Boolean = {
    msg.from match {
      case Some(user) => user.id.toString == myTelegramId
      case None       => false
    }
  }

  onCommand("report") { implicit msg =>
    if (checkAdminMessage(msg)) {
      createReport().flatMap { report =>
        reply(report).map(_ => ())
      }
    } else {
      reply("You are not allowed to use this command!").map(_ => ())
    }
  }

  onCommand("processunhandled") { implicit msg =>
    if (checkAdminMessage(msg)) {
      controller.invoiceMonitor.processUnhandledInvoices().flatMap { dbs =>
        reply(s"Updated ${dbs.size} invoices").map(_ => ())
      }
    } else {
      reply("You are not allowed to use this command!").map(_ => ())
    }
  }

  onCommand("create") { implicit msg =>
    val vec = msg.text.get.trim.split(" ", 2).toVector
    val f = if (vec.size != 2) {
      reply("Usage: /create <message>").map(_ => ())
    } else {
      val str = vec(1)
      val id = msg.chat.id
      controller.invoiceMonitor
        .processMessage(message = str,
                        noTwitter = false,
                        nodeIdOpt = None,
                        telegramId = Some(id),
                        nostrKey = None)
        .flatMap { db =>
          val replyF = reply(db.invoice.toString)

          val url =
            s"https://opreturnbot.com/qr?string=${URLEncoder.encode("lightning:" + db.invoice.toString, "UTF-8")}&width=300&height=300"
          val qrF = sendTelegramPhoto(url, id.toString)

          for {
            _ <- replyF
            _ <- qrF
          } yield ()
        }
    }

    f.failed.foreach(err =>
      logger.error(s"Error processing create command", err))

    f
  }

  private val http = Http()

  def sendTelegramMessage(
      message: String,
      telegramId: String = myTelegramId): Future[Unit] = {
    val url = s"https://api.telegram.org/bot$telegramCreds/sendMessage" +
      s"?chat_id=${URLEncoder.encode(telegramId, "UTF-8")}" +
      s"&text=${URLEncoder.encode(message.trim, "UTF-8")}"

    http.singleRequest(Get(url)).map(_ => ())
  }

  def sendTelegramPhoto(
      photoUrl: String,
      telegramId: String = myTelegramId): Future[Unit] = {
    val url = s"https://api.telegram.org/bot$telegramCreds/sendPhoto" +
      s"?chat_id=${URLEncoder.encode(telegramId, "UTF-8")}" +
      s"&photo=${URLEncoder.encode(photoUrl.trim, "UTF-8")}"

    http.singleRequest(Get(url)).map(_ => ())
  }

  def handleTelegram(
      rHash: Sha256Digest,
      invoice: LnInvoice,
      invoiceDb: InvoiceDb,
      tweetOpt: Option[Tweet],
      nostrOpt: Option[Sha256Digest],
      message: String,
      feeRate: SatoshisPerVirtualByte,
      txDetails: TxDetails,
      totalProfit: CurrencyUnit,
      totalChainFees: CurrencyUnit): Future[Unit] = {
    val amount = invoice.amount.get.toSatoshis
    val profit = amount - txDetails.totalFees

    val deliveryMethod = if (invoiceDb.nostrKey.isDefined) {
      "Nostr"
    } else if (invoiceDb.telegramIdOpt.isDefined) {
      "Telegram"
    } else if (invoiceDb.nodeIdOpt.isDefined) {
      "Lightning Onion Message"
    } else "Web"

    val tweetLine = tweetOpt match {
      case Some(tweet) =>
        s"https://twitter.com/OP_RETURN_Bot/status/${tweet.id}"
      case None => "Hidden"
    }

    val nostrLine = nostrOpt match {
      case Some(nostr) => nostr.hex
      case None        => "Hidden"
    }

    val telegramMsg =
      s"""
         |🔔 🔔 NEW OP_RETURN 🔔 🔔
         |Message: $message
         |Delivery: $deliveryMethod
         |rhash: ${rHash.hex}
         |tx: https://mempool.space/tx/${txDetails.txId.hex}
         |tweet: $tweetLine
         |nostr: $nostrLine
         |
         |fee rate: $feeRate
         |invoice amount: ${printAmount(amount)}
         |tx fee: ${printAmount(txDetails.totalFees)}
         |profit: ${printAmount(profit)}
         |
         |total chain fees: ${printAmount(totalChainFees)}
         |total profit: ${printAmount(totalProfit)}
         |""".stripMargin

    sendTelegramMessage(telegramMsg, myTelegramId)
  }

  def handleTelegramUserPurchase(
      telegramId: Long,
      txDetails: TxDetails): Future[Unit] = {
    val telegramMsg =
      s"""
         |OP_RETURN Created!
         |
         |https://mempool.space/tx/${txDetails.txId.hex}
         |""".stripMargin

    sendTelegramMessage(telegramMsg, telegramId.toString)
  }

  private def createReport(): Future[String] = {
    invoiceDAO.completed().map { completed =>
      val chainFees = completed.flatMap(_.chainFeeOpt).sum
      val profit = completed.flatMap(_.profitOpt).sum
      val vbytes = completed.flatMap(_.txOpt.map(_.vsize)).sum

      s"""
         |Total OP_RETURNs: ${intFormatter.format(completed.size)}
         |Total chain size: ${printSize(vbytes)}
         |Total chain fees: ${printAmount(chainFees)}
         |Total profit: ${printAmount(profit)}
         |""".stripMargin
    }
  }

  private def printSize(size: Long): String = {
    if (size < 1000) {
      s"${currencyFormatter.format(size).tail} vbytes"
    } else if (size < 1000000) {
      s"${currencyFormatter.format(size / 1000.0).tail} vKB"
    } else if (size < 1000000000) {
      s"${currencyFormatter.format(size / 1000000.0).tail} vMB"
    } else {
      s"${currencyFormatter.format(size / 1000000000.0).tail} vGB"
    }
  }

  private def printAmount(amount: CurrencyUnit): String = {
    intFormatter.format(amount.satoshis.toLong) + " sats"
  }
}
