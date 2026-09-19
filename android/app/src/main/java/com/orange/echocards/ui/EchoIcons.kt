package com.orange.echocards.ui

import androidx.annotation.DrawableRes
import com.composables.icons.lucide.R as LucideR

/**
 * 应用使用的图标，全部来自 Lucide（com.composables:icons-lucide-android，1665 个矢量图标）。
 *
 * AGP 9 使用非传递 R 类，app 模块的 R 不包含依赖库资源，必须通过库自身的 R 引用；
 * 这里集中收口，UI 代码只依赖语义化名字，也方便日后整体换图标集。
 */
internal object EchoIcons {
    /** 返回上一级。 */
    @DrawableRes val Back = LucideR.drawable.lucide_ic_chevron_left

    /** 编辑卡组。 */
    @DrawableRes val Edit = LucideR.drawable.lucide_ic_pencil

    /** 底部导航：首页。 */
    @DrawableRes val Home = LucideR.drawable.lucide_ic_house

    /** 底部导航：我的。 */
    @DrawableRes val Mine = LucideR.drawable.lucide_ic_user

    /** 搜索卡组。 */
    @DrawableRes val Search = LucideR.drawable.lucide_ic_search

    /** 新建卡组 / 添加卡片。 */
    @DrawableRes val Add = LucideR.drawable.lucide_ic_plus

    /** 导入卡组（JSON）。 */
    @DrawableRes val Import = LucideR.drawable.lucide_ic_upload

    /** 退出学习。 */
    @DrawableRes val Close = LucideR.drawable.lucide_ic_x

    /** 删除卡片。 */
    @DrawableRes val Delete = LucideR.drawable.lucide_ic_trash_2

    /** 切换学习方式。 */
    @DrawableRes val SwitchMode = LucideR.drawable.lucide_ic_arrow_left_right

    /** 朗读。 */
    @DrawableRes val Speak = LucideR.drawable.lucide_ic_play

    /** 停止朗读。 */
    @DrawableRes val Stop = LucideR.drawable.lucide_ic_square

    /** 开始识别（探针页）。 */
    @DrawableRes val Mic = LucideR.drawable.lucide_ic_mic

    /** 重试加载。 */
    @DrawableRes val Retry = LucideR.drawable.lucide_ic_rotate_cw
}
