package lila.security

import lila.user.{ User, UserContext }

trait SecurityHelper {

  def isGranted(permission: Permission)(implicit ctx: UserContext): Boolean =
    ctx.me ?? Granter(permission)

  def isGranted(permission: Permission, user: User): Boolean =
    Granter(permission)(user)
}
