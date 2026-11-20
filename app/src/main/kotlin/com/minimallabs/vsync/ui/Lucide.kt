package com.minimallabs.vsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.composables.icons.lucide.R as L

/** Lucide icons (the set the Flutter app used), from com.composables:icons-lucide. */
object Lucide {
    private val v: @Composable (Int) -> ImageVector = { ImageVector.vectorResource(it) }

    val House @Composable get() = v(L.drawable.lucide_ic_house)
    val CalendarDays @Composable get() = v(L.drawable.lucide_ic_calendar_days)
    val BookOpen @Composable get() = v(L.drawable.lucide_ic_book_open)
    val UserRound @Composable get() = v(L.drawable.lucide_ic_user_round)
    val ClipboardCheck @Composable get() = v(L.drawable.lucide_ic_clipboard_check)
    val ListChecks @Composable get() = v(L.drawable.lucide_ic_list_checks)
    val CalendarClock @Composable get() = v(L.drawable.lucide_ic_calendar_clock)
    val BookText @Composable get() = v(L.drawable.lucide_ic_book_text)
    val CalendarRange @Composable get() = v(L.drawable.lucide_ic_calendar_range)
    val GraduationCap @Composable get() = v(L.drawable.lucide_ic_graduation_cap)
    val Fingerprint @Composable get() = v(L.drawable.lucide_ic_fingerprint_pattern)
    val FileUp @Composable get() = v(L.drawable.lucide_ic_file_up)
    val PlaneTakeoff @Composable get() = v(L.drawable.lucide_ic_plane_takeoff)
    val Users @Composable get() = v(L.drawable.lucide_ic_users)
    val Globe @Composable get() = v(L.drawable.lucide_ic_globe)
    val Ellipsis @Composable get() = v(L.drawable.lucide_ic_ellipsis)
    val KeyRound @Composable get() = v(L.drawable.lucide_ic_key_round)
    val Moon @Composable get() = v(L.drawable.lucide_ic_moon)
    val Palette @Composable get() = v(L.drawable.lucide_ic_palette)
    val RefreshCw @Composable get() = v(L.drawable.lucide_ic_refresh_cw)
    val MailCheck @Composable get() = v(L.drawable.lucide_ic_mail_check)
    val ShieldCheck @Composable get() = v(L.drawable.lucide_ic_shield_check)
    val LogOut @Composable get() = v(L.drawable.lucide_ic_log_out)
    val ChevronRight @Composable get() = v(L.drawable.lucide_ic_chevron_right)
    val ChevronLeft @Composable get() = v(L.drawable.lucide_ic_chevron_left)
    val ArrowLeft @Composable get() = v(L.drawable.lucide_ic_arrow_left)
    val Clock @Composable get() = v(L.drawable.lucide_ic_clock)
    val Coffee @Composable get() = v(L.drawable.lucide_ic_coffee)
    val CirclePlus @Composable get() = v(L.drawable.lucide_ic_circle_plus)
    val Pencil @Composable get() = v(L.drawable.lucide_ic_pencil)
    val CalendarX @Composable get() = v(L.drawable.lucide_ic_calendar_x)
    val Calculator @Composable get() = v(L.drawable.lucide_ic_calculator)
    val Wallet @Composable get() = v(L.drawable.lucide_ic_wallet)
    val Rocket @Composable get() = v(L.drawable.lucide_ic_rocket)
    val Trash @Composable get() = v(L.drawable.lucide_ic_trash_2)
    val Upload @Composable get() = v(L.drawable.lucide_ic_upload)
    val Hand @Composable get() = v(L.drawable.lucide_ic_hand)
    val Info @Composable get() = v(L.drawable.lucide_ic_info)
    val Code @Composable get() = v(L.drawable.lucide_ic_code)
}
