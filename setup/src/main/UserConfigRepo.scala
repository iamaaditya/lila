package lila.setup

import lila.user.User

import tube.{ userConfigTube, filterConfigTube }
import lila.game.Game
import lila.db.Implicits._
import lila.db.api._

import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits._

import reactivemongo.api._
import reactivemongo.bson._

private[setup] object UserConfigRepo {

  def update(user: User)(map: UserConfig ⇒ UserConfig): Funit =
    config(user) flatMap { c ⇒ $save(map(c)) }

  def config(user: User): Fu[UserConfig] =
    $find byId user.id recover {
      case e: lila.db.DbException ⇒ {
        logwarn("Can't load config: " + e.getMessage)
        none[UserConfig]
      }
    } map (_ | UserConfig.default(user.id))

  def filter(user: User): Fu[FilterConfig] = $primitive.one(
    $select(user.id),
    "filter")(_.asOpt[FilterConfig]) map (_ | FilterConfig.default)
}
