/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.electrum.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.{BinaryData, Transaction}
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet
import fr.acinq.eclair.db.sqlite.SqliteUtils

import scala.collection.immutable.Queue

class SqliteTxCache(sqlite: Connection) extends ElectrumWallet.TxCache {
  import SqliteUtils._

  val DB_NAME = "txs"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement()) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION) // there is only one version currently deployed
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS txs (tx_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
  }

  def put(tx: Transaction): Unit = {
    val data = Transaction.write(tx)
    using (sqlite.prepareStatement("UPDATE txs SET data=? WHERE tx_id=?")) { update =>
      update.setBytes(1, data)
      update.setBytes(2, tx.txid)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO txs VALUES (?, ?)")) { statement =>
          statement.setBytes(1, tx.txid)
          statement.setBytes(2, data)
          statement.executeUpdate()
        }
      }
    }
  }

  def get(txid: BinaryData) : Option[Transaction] = {
    using(sqlite.prepareStatement("SELECT data FROM txs WHERE tx_id = ?")) { statement =>
      statement.setBytes(1, txid)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(Transaction.read(BinaryData(rs.getBytes("data"))))
      } else {
        None
      }
    }
  }

  def delete(txid: BinaryData) : Int = {
    using(sqlite.prepareStatement("DELETE FROM txs WHERE tx_id=?")) { statement =>
      statement.setBytes(1, txid)
      statement.executeUpdate()
    }
  }

  def getAll: Seq[Transaction] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT data FROM txs")
      var q: Queue[Transaction] = Queue()
      while (rs.next()) {
        q = q :+ Transaction.read(BinaryData(rs.getBytes("data")))
      }
      q
    }
  }
}
