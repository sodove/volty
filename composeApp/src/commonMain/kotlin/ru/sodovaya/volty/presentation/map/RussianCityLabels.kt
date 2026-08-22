package ru.sodovaya.volty.presentation.map

internal data class MapCityLabel(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/** Extra Russian city anchors kept visible when the base style is zoomed out. */
internal val russianCityLabels = listOf(
    MapCityLabel("Москва", 55.7558, 37.6173),
    MapCityLabel("Санкт-Петербург", 59.9343, 30.3351),
    MapCityLabel("Мурманск", 68.9707, 33.0749),
    MapCityLabel("Архангельск", 64.5393, 40.5187),
    MapCityLabel("Ярославль", 57.6261, 39.8845),
    MapCityLabel("Нижний Новгород", 56.3269, 44.0059),
    MapCityLabel("Казань", 55.7879, 49.1233),
    MapCityLabel("Самара", 53.1959, 50.1002),
    MapCityLabel("Саратов", 51.5336, 46.0343),
    MapCityLabel("Волгоград", 48.7080, 44.5133),
    MapCityLabel("Ростов-на-Дону", 47.2357, 39.7015),
    MapCityLabel("Краснодар", 45.0355, 38.9753),
    MapCityLabel("Воронеж", 51.6608, 39.2003),
    MapCityLabel("Уфа", 54.7388, 55.9721),
    MapCityLabel("Пермь", 58.0105, 56.2502),
    MapCityLabel("Екатеринбург", 56.8389, 60.6057),
    MapCityLabel("Челябинск", 55.1644, 61.4368),
    MapCityLabel("Тюмень", 57.1530, 65.5343),
    MapCityLabel("Новосибирск", 55.0084, 82.9357),
    MapCityLabel("Омск", 54.9885, 73.3242),
    MapCityLabel("Красноярск", 56.0153, 92.8932),
    MapCityLabel("Иркутск", 52.2869, 104.3050),
    MapCityLabel("Якутск", 62.0355, 129.6755),
    MapCityLabel("Хабаровск", 48.4802, 135.0719),
    MapCityLabel("Владивосток", 43.1155, 131.8855),
)
