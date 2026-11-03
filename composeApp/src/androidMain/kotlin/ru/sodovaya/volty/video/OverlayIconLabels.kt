package ru.sodovaya.volty.video

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.video.OverlayIconGroup
import volty.composeapp.generated.resources.*

/** The picker's group headings, in telemetry-studio's icons.ts order. */
internal fun iconGroupLabel(group: OverlayIconGroup): StringResource = when (group) {
    OverlayIconGroup.POWER -> Res.string.video_icon_group_power
    OverlayIconGroup.BATTERY -> Res.string.video_icon_group_battery
    OverlayIconGroup.TEMPERATURE -> Res.string.video_icon_group_temperature
    OverlayIconGroup.SPEED -> Res.string.video_icon_group_speed
    OverlayIconGroup.GPS -> Res.string.video_icon_group_gps
    OverlayIconGroup.TRANSPORT -> Res.string.video_icon_group_transport
    OverlayIconGroup.DATA -> Res.string.video_icon_group_data
    OverlayIconGroup.GENERAL -> Res.string.video_icon_group_general
}

/** An icon's spoken and shown name; an unknown one reads as its Font Awesome name. */
@Composable
internal fun overlayIconLabel(name: String): String = when (name) {
    "bolt" -> stringResource(Res.string.video_icon_bolt)
    "plug" -> stringResource(Res.string.video_icon_plug)
    "car-battery" -> stringResource(Res.string.video_icon_car_battery)
    "charging-station" -> stringResource(Res.string.video_icon_charging_station)
    "battery-full" -> stringResource(Res.string.video_icon_battery_full)
    "battery-three-quarters" -> stringResource(Res.string.video_icon_battery_three_quarters)
    "battery-half" -> stringResource(Res.string.video_icon_battery_half)
    "battery-quarter" -> stringResource(Res.string.video_icon_battery_quarter)
    "battery-empty" -> stringResource(Res.string.video_icon_battery_empty)
    "temperature-high" -> stringResource(Res.string.video_icon_temperature_high)
    "temperature-half" -> stringResource(Res.string.video_icon_temperature_half)
    "temperature-low" -> stringResource(Res.string.video_icon_temperature_low)
    "fire" -> stringResource(Res.string.video_icon_fire)
    "snowflake" -> stringResource(Res.string.video_icon_snowflake)
    "gauge" -> stringResource(Res.string.video_icon_gauge)
    "gauge-simple" -> stringResource(Res.string.video_icon_gauge_simple)
    "arrow-up" -> stringResource(Res.string.video_icon_arrow_up)
    "arrow-down" -> stringResource(Res.string.video_icon_arrow_down)
    "arrow-right" -> stringResource(Res.string.video_icon_arrow_right)
    "arrows-up-down" -> stringResource(Res.string.video_icon_arrows_up_down)
    "rocket" -> stringResource(Res.string.video_icon_rocket)
    "location-dot" -> stringResource(Res.string.video_icon_location_dot)
    "satellite" -> stringResource(Res.string.video_icon_satellite)
    "compass" -> stringResource(Res.string.video_icon_compass)
    "map-pin" -> stringResource(Res.string.video_icon_map_pin)
    "car" -> stringResource(Res.string.video_icon_car)
    "motorcycle" -> stringResource(Res.string.video_icon_motorcycle)
    "bicycle" -> stringResource(Res.string.video_icon_bicycle)
    "road" -> stringResource(Res.string.video_icon_road)
    "chart-line" -> stringResource(Res.string.video_icon_chart_line)
    "chart-bar" -> stringResource(Res.string.video_icon_chart_bar)
    "signal" -> stringResource(Res.string.video_icon_signal)
    "wave" -> stringResource(Res.string.video_icon_wave)
    "percent" -> stringResource(Res.string.video_icon_percent)
    "gear" -> stringResource(Res.string.video_icon_gear)
    "clock" -> stringResource(Res.string.video_icon_clock)
    "stopwatch" -> stringResource(Res.string.video_icon_stopwatch)
    "weight" -> stringResource(Res.string.video_icon_weight)
    "circle-info" -> stringResource(Res.string.video_icon_circle_info)
    "triangle-exclamation" -> stringResource(Res.string.video_icon_triangle_exclamation)
    "shield" -> stringResource(Res.string.video_icon_shield)
    "eye" -> stringResource(Res.string.video_icon_eye)
    "star" -> stringResource(Res.string.video_icon_star)
    "heart" -> stringResource(Res.string.video_icon_heart)
    "flag" -> stringResource(Res.string.video_icon_flag)
    "bell" -> stringResource(Res.string.video_icon_bell)
    "hashtag" -> stringResource(Res.string.video_icon_hashtag)
    else -> name
}
