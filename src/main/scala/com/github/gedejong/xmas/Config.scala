package com.github.gedejong.xmas

import com.typesafe.config.ConfigFactory

object Config {
  private[this] val config = ConfigFactory.load()

  val retailer = config.getString("xmas-tree-server.retailer")
  val token = config.getString("xmas-tree-server.token")
}
