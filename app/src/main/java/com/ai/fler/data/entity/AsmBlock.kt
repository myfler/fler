package com.ai.fler.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ASM 反汇编块（Blutter asm 完整产物）。
 *
 * 与 [DartMethod.srcCode]（裸指令，来自 buildFunctionAsm）不同，本表存的是
 * Blutter DumpCode 产出的完整反汇编——含语义注释（EnterFrame / LoadField /
 * InitAsync）与 pp 槽解引用（`// [pp+0x..] "字符串"`、`// [pp+0x..] Field <..>`），
 * 是裸指令的超集，可读性远高于 src_code。
 *
 * 导入时机：分析阶段 5（引擎产物 outDir/asm 尚在时）自动导入；或经 MCP
 * `asm_import` 对已保存的产物目录手动补导（如 blutter_tmp 被清、用户有手动副本）。
 *
 * 匹配键：vaddr（dart_methods.function_offset 与 asm 段地址同坐标，
 * libapp 上 fileOffset == functionOffset）。
 */
@Entity(
    tableName = "asm_blocks",
    foreignKeys = [
        ForeignKey(
            entity = Analysis::class,
            parentColumns = ["id"],
            childColumns = ["analysis_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = DartMethod::class,
            parentColumns = ["id"],
            childColumns = ["method_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["analysis_id"]),
        Index(value = ["method_id"]),
        Index(value = ["analysis_id", "vaddr"])
    ]
)
data class AsmBlock(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "analysis_id")
    val analysisId: Long,

    /** 关联的 dart_methods.id（经 vaddr 反查）；未知时为兜底方法 id。 */
    @ColumnInfo(name = "method_id")
    val methodId: Long,

    /** 方法起始 vaddr（与 dart_methods.function_offset 同坐标）。 */
    @ColumnInfo(name = "vaddr")
    val vaddr: Long,

    /** 方法字节大小（asm 段 `size:`；空壳段 size=-0x1 不入库）。 */
    @ColumnInfo(name = "size")
    val size: Long,

    /** 来源库（asm 文件头 `// lib: , url:` 的 url，如 EYq）。 */
    @ColumnInfo(name = "url")
    val url: String? = null,

    /** 完整反汇编文本（`// ** addr: ...` 起，含语义注释 + pp 解引用）。 */
    @ColumnInfo(name = "body")
    val body: String
)
