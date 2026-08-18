package com.ai.fler.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ai.fler.data.AppDatabase
import com.ai.fler.data.dao.AddressMappingDao
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.AsmBlockDao
import com.ai.fler.data.dao.DartCallGraphDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.DartObjectDao
import com.ai.fler.data.dao.EnumMapDao
import com.ai.fler.data.dao.HookScriptDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.data.dao.McpToolStatDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.dao.ProjectDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据库依赖注入模块。
 *
 * 提供 AppDatabase 和各 DAO 的单例。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .addMigrations(
                MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    /** dart_call_edges 完整建表 SQL（含外键，Room 校验要求与实体一致）。 */
    private val CREATE_DART_CALL_EDGES =
        "CREATE TABLE IF NOT EXISTS `dart_call_edges` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`analysis_id` INTEGER NOT NULL, " +
            "`caller_method_id` INTEGER NOT NULL, " +
            "`caller_name` TEXT NOT NULL, " +
            "`caller_vaddr` INTEGER NOT NULL, " +
            "`callee_method_id` INTEGER, " +
            "`callee_name` TEXT NOT NULL, " +
            "`callee_vaddr` INTEGER NOT NULL, " +
            "`callee_kind` TEXT NOT NULL, " +
            "`site_vaddr` INTEGER NOT NULL, " +
            "FOREIGN KEY(`caller_method_id`) REFERENCES `dart_methods`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE, " +
            "FOREIGN KEY(`analysis_id`) REFERENCES `analyses`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE" +
            ")"

    private val CREATE_INDEXES = listOf(
        "CREATE INDEX IF NOT EXISTS `index_dart_call_edges_analysis_id` ON `dart_call_edges` (`analysis_id`)",
        "CREATE INDEX IF NOT EXISTS `index_dart_call_edges_caller_method_id` ON `dart_call_edges` (`caller_method_id`)",
        "CREATE INDEX IF NOT EXISTS `index_dart_call_edges_callee_method_id` ON `dart_call_edges` (`callee_method_id`)",
        "CREATE INDEX IF NOT EXISTS `index_dart_call_edges_callee_vaddr` ON `dart_call_edges` (`callee_vaddr`)"
    )

    /** 3 → 4：新增 dart_edges 表（Dart 方法调用图边，真实交叉引用）。 */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_DART_CALL_EDGES)
            CREATE_INDEXES.forEach { db.execSQL(it) }
        }
    }

    /** 4 → 5：重建 dart_call_edges（早期迁移缺外键导致 Room 校验失败）。 */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `dart_call_edges`")
            db.execSQL(CREATE_DART_CALL_EDGES)
            CREATE_INDEXES.forEach { db.execSQL(it) }
        }
    }

    /** 5 → 6：dart_methods 增加 (analysis_id, function_offset) 复合索引，加速调用图构建分页扫描。 */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dart_methods_analysis_id_function_offset` " +
                    "ON `dart_methods` (`analysis_id`, `function_offset`)"
            )
        }
    }

    /** 6 → 7：pp_entries 增加 (analysis_id, type) 和 (analysis_id, caller_count) 复合索引，加速 PP 字符串筛选和 top caller 排序。 */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pp_entries_analysis_id_type` " +
                    "ON `pp_entries` (`analysis_id`, `type`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pp_entries_analysis_id_caller_count` " +
                    "ON `pp_entries` (`analysis_id`, `caller_count`)"
            )
        }
    }

    /** 7 → 8：新增 mcp_tool_stats 表（MCP 工具调用统计）。 */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `mcp_tool_stats` (" +
                    "`tool` TEXT NOT NULL, " +
                    "`calls` INTEGER NOT NULL, " +
                    "`errors` INTEGER NOT NULL, " +
                    "`total_ms` INTEGER NOT NULL, " +
                    "`max_ms` INTEGER NOT NULL, " +
                    "`last_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`tool`)" +
                    ")"
            )
        }
    }

    /** 8 → 9：新增 hook_scripts 表（Frida Hook 脚本落地管理）。 */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `hook_scripts` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, " +
                    "`is_preset` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL" +
                    ")"
            )
        }
    }

    /** 9 → 10：新增 objs（对象池对象索引）与 enum_map（枚举索引映射）表。 */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `objs` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`analysis_id` INTEGER NOT NULL, " +
                    "`obj_address` INTEGER NOT NULL, " +
                    "`class_name` TEXT, " +
                    "`field_hint` TEXT, " +
                    "FOREIGN KEY(`analysis_id`) REFERENCES `analyses`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE" +
                    ")"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_objs_analysis_id` ON `objs` (`analysis_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_objs_analysis_id_class_name` ON `objs` (`analysis_id`, `class_name`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_objs_analysis_id_obj_address` ON `objs` (`analysis_id`, `obj_address`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `enum_map` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`analysis_id` INTEGER NOT NULL, " +
                    "`class_name` TEXT NOT NULL, " +
                    "`enum_index` INTEGER NOT NULL, " +
                    "`enum_name` TEXT NOT NULL, " +
                    "FOREIGN KEY(`analysis_id`) REFERENCES `analyses`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE" +
                    ")"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_enum_map_analysis_id` ON `enum_map` (`analysis_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_enum_map_analysis_id_class_name` ON `enum_map` (`analysis_id`, `class_name`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_enum_map_analysis_id_enum_index` ON `enum_map` (`analysis_id`, `enum_index`)"
            )
        }
    }

    /** 10 → 11：新增 asm_blocks（Blutter asm 完整反汇编导入表）。 */
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `asm_blocks` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`analysis_id` INTEGER NOT NULL, " +
                    "`method_id` INTEGER NOT NULL, " +
                    "`vaddr` INTEGER NOT NULL, " +
                    "`size` INTEGER NOT NULL, " +
                    "`url` TEXT, " +
                    "`body` TEXT NOT NULL, " +
                    "FOREIGN KEY(`analysis_id`) REFERENCES `analyses`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`method_id`) REFERENCES `dart_methods`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE" +
                    ")"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_asm_blocks_analysis_id` ON `asm_blocks` (`analysis_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_asm_blocks_method_id` ON `asm_blocks` (`method_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_asm_blocks_analysis_id_vaddr` ON `asm_blocks` (`analysis_id`, `vaddr`)"
            )
        }
    }

    @Provides
    fun provideProjectDao(db: AppDatabase): ProjectDao = db.projectDao()    @Provides
    fun provideAnalysisDao(db: AppDatabase): AnalysisDao = db.analysisDao()

    @Provides
    fun provideDartClassDao(db: AppDatabase): DartClassDao = db.dartClassDao()

    @Provides
    fun provideDartMethodDao(db: AppDatabase): DartMethodDao = db.dartMethodDao()

    @Provides
    fun providePpEntryDao(db: AppDatabase): PpEntryDao = db.ppEntryDao()

    @Provides
    fun provideLibraryDao(db: AppDatabase): LibraryDao = db.libraryDao()

    @Provides
    fun provideAddressMappingDao(db: AppDatabase): AddressMappingDao = db.addressMappingDao()

    @Provides
    fun provideDartCallGraphDao(db: AppDatabase): DartCallGraphDao = db.dartCallGraphDao()

    @Provides
    fun provideMcpToolStatDao(db: AppDatabase): McpToolStatDao = db.mcpToolStatDao()

    @Provides
    fun provideHookScriptDao(db: AppDatabase): HookScriptDao = db.hookScriptDao()

    @Provides
    fun provideDartObjectDao(db: AppDatabase): DartObjectDao = db.dartObjectDao()

    @Provides
    fun provideEnumMapDao(db: AppDatabase): EnumMapDao = db.enumMapDao()

    @Provides
    fun provideAsmBlockDao(db: AppDatabase): AsmBlockDao = db.asmBlockDao()
}
