package de.jannikarndt.sqlautodoc.MSSQL

import com.typesafe.scalalogging.Logger
import de.jannikarndt.sqlautodoc.configuration.Options
import de.jannikarndt.sqlautodoc.model._
import slick.jdbc
import slick.jdbc.SQLServerProfile
import slick.jdbc.SQLServerProfile.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

object SqlServerDoc {

    val logger = Logger(this.getClass)

    def QuerySystemTables(options: Options): Seq[TableInfo] = {

        logger.debug(s"Connecting to SQL Server ${options.connection.url} with user ${options.connection.user}")

        var db: jdbc.SQLServerProfile.backend.DatabaseDef = null

        try {
            db = Database.forURL(
                options.connection.url,
                driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver",
                user = options.connection.user,
                password = options.connection.password)

            val doc = new SqlServerDoc(db)

            Await.result(doc.getTableInfo, options.timeout seconds)
        } finally {
            db.close
        }
    }
}

class SqlServerDoc(val db: SQLServerProfile.backend.DatabaseDef) {
    private lazy val schemas = TableQuery[Schemas]
    private lazy val sysobjects = TableQuery[SysObjects]
    private lazy val properties = TableQuery[ExtendedProperties]
    private lazy val sysColumns = TableQuery[SysColumns]
    private lazy val sysTypes = TableQuery[SysTypes]


    def getTableInfo: Future[Seq[TableInfo]] = {
        queryTables().flatMap(userTables =>
            Future(
                userTables.map(table =>
                    TableInfo(table._1, table._2, table._3, table._5,
                        queryColumns(table._3).map(col =>
                            MssqlColumnInfo(col._3, col._2, col._4, col._5, col._6, col._7, queryProperties(col._2, col._1))
                        )
                    )
                )
            )
        )
    }

    private def queryTables(): Future[Seq[(String, String, Int, String, String)]] = {
        val tablesQuery = for {
            schema <- schemas.filterNot(_.name.inSet(Seq("dbo", "guest", "INFORMATION_SCHEMA", "sys"))).filterNot(_.name.startsWith("db_"))
            tables <- sysobjects.filter(_.xtype === "U") if tables.uid === schema.schema_id
            prop <- properties.filter(_.minor_id === 0).filter(_.theclass === 1) if tables.id === prop.major_id
        } yield (schema.name, tables.name, tables.id, prop.name, prop.value.asColumnOf[String])

        db.run(tablesQuery.result)
    }

    private def queryColumns(tableId: Int): Seq[(Int, Int, String, String, Int, Boolean, String)] = {
        val columnsQuery = for {
            column <- sysColumns.filter(_.id === tableId)
            colType <- sysTypes.filterNot(_.name === "sysname") if colType.xtype === column.xtype
        } yield (column.colid, column.id, column.name, colType.name, column.length, column.isnullable, column.default)

        Await.result(db.run(columnsQuery.result), 10 seconds).sortBy(_._1)
    }

    private def queryProperties(majorId: Rep[Int], minorId: Rep[Int]): Seq[(String, String)] = {
        val propsQuery = properties.filter(_.major_id === majorId).filter(_.minor_id === minorId).filter(_.theclass === 1)
            .map(col => (col.name, col.value.asColumnOf[String]))

        Await.result(db.run(propsQuery.result), 10 seconds)
    }
}
