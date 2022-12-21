package config

import akka.actor.ActorSystem
import com.danielasfregola.twitter4s.TwitterRestClient
import com.danielasfregola.twitter4s.entities.{AccessToken, ConsumerToken}
import com.translnd.rotator.config.TransLndAppConfig
import com.typesafe.config.Config
import grizzled.slf4j.Logging
import models.InvoiceDAO
import org.bitcoins.commons.config._
import org.bitcoins.core.hd.HDPurposes
import org.bitcoins.core.wallet.keymanagement.KeyManagerParams
import org.bitcoins.crypto.AesPassword
import org.bitcoins.db._
import org.bitcoins.keymanager.WalletStorage
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.keymanager.config.KeyManagerAppConfig
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.config.{LndInstance, LndInstanceLocal}
import org.scalastr.client.NostrClient
import org.scalastr.core.NostrEvent

import scala.jdk.CollectionConverters._
import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.concurrent._
import scala.util.Properties

/** Configuration for the Bitcoin-S wallet
  *
  * @param directory
  *   The data directory of the wallet
  * @param conf
  *   Optional sequence of configuration overrides
  */
case class OpReturnBotAppConfig(
    private val directory: Path,
    override val configOverrides: Vector[Config])(implicit system: ActorSystem)
    extends DbAppConfig
    with JdbcProfileComponent[OpReturnBotAppConfig]
    with DbManagement
    with Logging {
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  override val moduleName: String = OpReturnBotAppConfig.moduleName
  override type ConfigType = OpReturnBotAppConfig

  override val appConfig: OpReturnBotAppConfig = this

  import profile.api._

  override def newConfigOfType(configs: Vector[Config]): OpReturnBotAppConfig =
    OpReturnBotAppConfig(directory, configs)

  val baseDatadir: Path = directory

  implicit lazy val transLndConfig: TransLndAppConfig =
    TransLndAppConfig(directory, configOverrides)

  lazy val lndDataDir: Path =
    Paths.get(config.getString(s"bitcoin-s.lnd.datadir"))

  lazy val lndBinary: File =
    Paths.get(config.getString(s"bitcoin-s.lnd.binary")).toFile

  lazy val telegramCreds: String =
    config.getStringOrElse(s"bitcoin-s.$moduleName.telegramCreds", "")

  lazy val telegramId: String =
    config.getStringOrElse(s"bitcoin-s.$moduleName.telegramId", "")

  lazy val twitterConsumerKey: String =
    config.getString(s"twitter.consumer.key")

  lazy val twitterConsumerSecret: String =
    config.getString(s"twitter.consumer.secret")

  lazy val bannedWords: Vector[String] = {
    val list = config.getStringList(s"twitter.banned-words")
    list.asScala.toVector
  }

  lazy val consumerToken: ConsumerToken =
    ConsumerToken(twitterConsumerKey, twitterConsumerSecret)

  lazy val twitterAccessKey: String = config.getString(s"twitter.access.key")

  lazy val twitterAccessSecret: String =
    config.getString(s"twitter.access.secret")

  lazy val accessToken: AccessToken =
    AccessToken(twitterAccessKey, twitterAccessSecret)

  lazy val twitterClient: TwitterRestClient =
    TwitterRestClient.withActorSystem(consumerToken, accessToken)(system)

  lazy val kmConf: KeyManagerAppConfig =
    KeyManagerAppConfig(baseDatadir, configOverrides)

  lazy val seedPath: Path = {
    kmConf.seedFolder.resolve("encrypted-bitcoin-s-seed.json")
  }

  lazy val kmParams: KeyManagerParams =
    KeyManagerParams(seedPath, HDPurposes.SegWit, network)

  lazy val aesPasswordOpt: Option[AesPassword] = kmConf.aesPasswordOpt
  lazy val bip39PasswordOpt: Option[String] = kmConf.bip39PasswordOpt

  lazy val nostrRelays: Vector[String] = {
    if (config.hasPath("nostr.relays")) {
      config.getStringList(s"nostr.relays").asScala.toVector
    } else Vector.empty
  }

  lazy val nostrClients: Vector[NostrClient] = {
    nostrRelays.map { relay =>
      new NostrClient(relay, None) {

        override def processEvent(
            subscriptionId: String,
            event: NostrEvent): Future[Unit] = {
          Future.unit
        }

        override def processNotice(notice: String): Future[Unit] = Future.unit
      }
    }
  }

  def seedExists(): Boolean = {
    WalletStorage.seedExists(seedPath)
  }

  def initialize(): Unit = {
    // initialize seed
    if (!seedExists()) {
      BIP39KeyManager.initialize(aesPasswordOpt = aesPasswordOpt,
                                 kmParams = kmParams,
                                 bip39PasswordOpt = bip39PasswordOpt) match {
        case Left(err) => sys.error(err.toString)
        case Right(_) =>
          logger.info("Successfully generated a seed and key manager")
      }
    }

    ()
  }

  override def start(): Future[Unit] = {
    transLndConfig.start().flatMap { _ =>
      logger.info(s"Initializing setup")

      if (Files.notExists(baseDatadir)) {
        Files.createDirectories(baseDatadir)
      }

      val numMigrations = migrate().migrationsExecuted
      logger.info(s"Applied $numMigrations")

      initialize()

      Future.sequence(nostrClients.map(_.start())).map(_ => ())
    }
  }

  override def stop(): Future[Unit] = Future.unit

  override lazy val dbPath: Path = baseDatadir

  lazy val lndInstance: LndInstance =
    LndInstanceLocal.fromDataDir(lndDataDir.toFile)

  lazy val lndRpcClient: LndRpcClient =
    new LndRpcClient(lndInstance, Some(lndBinary))

  override val allTables: List[TableQuery[Table[_]]] =
    List(InvoiceDAO()(ec, this).table)
}

object OpReturnBotAppConfig
    extends AppConfigFactoryBase[OpReturnBotAppConfig, ActorSystem] {

  val DEFAULT_DATADIR: Path = Paths.get(Properties.userHome, ".op-return-bot")

  override def fromDefaultDatadir(confs: Vector[Config] = Vector.empty)(implicit
      ec: ActorSystem): OpReturnBotAppConfig = {
    fromDatadir(DEFAULT_DATADIR, confs)
  }

  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      ec: ActorSystem): OpReturnBotAppConfig =
    OpReturnBotAppConfig(datadir, confs)

  override val moduleName: String = "opreturnbot"
}
