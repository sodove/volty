package ru.sodovaya.volty.notification

import ru.sodovaya.volty.domain.usecase.AlertKind
import ru.sodovaya.volty.domain.usecase.AlertMessage
import ru.sodovaya.volty.util.UnitSystem
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the pure [AlertNotificationFormatter] with the **real** `values/` and `values-ru/` string
 * files (parsed from source, since resources are not loadable in a JVM unit test), so a missing
 * string, a swapped argument or a wording change in either locale fails here instead of on a
 * rider's lock screen.
 *
 * What it cannot judge: that the platform picks the locale (that is `Context.getString`).
 */
class AlertNotificationTextResourcesTest {

    private fun load(dir: String): Map<String, String> {
        val file = File("src/main/res/$dir/alert_notifications.xml")
        assertTrue(file.exists(), "run from the composeApp module: ${file.absolutePath}")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val n = nodes.item(it)
            n.attributes.getNamedItem("name").nodeValue to n.textContent
        }
    }

    private fun lookup(table: Map<String, String>) = AlertStrings { string, args ->
        TestAlertStrings.substitute(table.getValue(string.resourceName), args)
    }

    private val en = load("values")
    private val ru = load("values-ru")

    private fun text(message: AlertMessage, table: Map<String, String>, units: UnitSystem = UnitSystem.METRIC) =
        AlertNotificationFormatter.format(message, units, lookup(table))

    @Test
    fun `both locales define exactly the strings the formatter asks for`() {
        val expected = AlertString.entries.map { it.resourceName }.toSet()
        assertEquals(expected, en.keys, "values/")
        assertEquals(expected, ru.keys, "values-ru/")
    }

    @Test
    fun `both locales take the same arguments in every string`() {
        val arg = Regex("%([0-9])[$]s")
        for (s in AlertString.entries) {
            val e = arg.findAll(en.getValue(s.resourceName)).map { it.groupValues[1] }.toSet()
            val r = arg.findAll(ru.getValue(s.resourceName)).map { it.groupValues[1] }.toSet()
            assertEquals(e, r, "${s.resourceName}: argument positions differ between locales")
        }
    }

    @Test
    fun `the english resources keep the wording the engine always used`() {
        for (s in AlertString.entries) {
            assertEquals(TestAlertStrings.ENGLISH.getValue(s), en.getValue(s.resourceName), s.resourceName)
        }
    }

    private fun m(kind: AlertKind, value: Float? = null, details: List<String> = emptyList()) =
        AlertMessage(kind, "Bike", value, details)

    @Test
    fun `russian formats every kind`() {
        fun ru(message: AlertMessage, units: UnitSystem = UnitSystem.METRIC) = text(message, this.ru, units)
        assertEquals("Высокое напряжение ячейки" to "Bike: макс. ячейка 4.25 В",
            ru(m(AlertKind.CELL_HIGH, 4.25f)).let { it.title to it.text })
        assertEquals("Низкое напряжение ячейки" to "Bike: мин. ячейка 2.80 В",
            ru(m(AlertKind.CELL_LOW, 2.8f)).let { it.title to it.text })
        assertEquals("Разбаланс ячеек" to "Bike: Δ 250 мВ",
            ru(m(AlertKind.CELL_DELTA, 250f)).let { it.title to it.text })
        assertEquals("Предупреждение о температуре" to "Bike: 52°C",
            ru(m(AlertKind.TEMPERATURE_WARN, 52f)).let { it.title to it.text })
        assertEquals("Высокая температура" to "Bike: 61°C",
            ru(m(AlertKind.TEMPERATURE_HIGH, 61f)).let { it.title to it.text })
        assertEquals("Перегрев мотора" to "Bike: мотор 118°C",
            ru(m(AlertKind.MOTOR_TEMP_HIGH, 118f)).let { it.title to it.text })
        assertEquals("Перегрев контроллера" to "Bike: контроллер 95°C",
            ru(m(AlertKind.ESC_TEMP_HIGH, 95f)).let { it.title to it.text })
        assertEquals("Низкий заряд" to "Bike: 14%",
            ru(m(AlertKind.SOC_LOW, 14f)).let { it.title to it.text })
        assertEquals("Отсечка разряда" to "Bike: 8% — остановитесь",
            ru(m(AlertKind.SOC_CUTOFF, 8f)).let { it.title to it.text })
        assertEquals("Высокий ШИМ" to "Bike: ШИМ 87%",
            ru(m(AlertKind.DUTY_HIGH, 87f)).let { it.title to it.text })
        assertEquals("Зарядка завершена" to "Bike: заряжено на 100%",
            ru(m(AlertKind.CHARGE_COMPLETE)).let { it.title to it.text })
        assertEquals("Нет связи" to "Bike: соединение потеряно",
            ru(m(AlertKind.DISCONNECT)).let { it.title to it.text })
        assertEquals("Ошибка контроллера" to "Bike: DRV, OVP",
            ru(m(AlertKind.CONTROLLER_FAULT, details = listOf("DRV", "OVP"))).let { it.title to it.text })
        assertEquals("Ошибка батареи" to "Bike: discharge disabled",
            ru(m(AlertKind.BMS_FAULT, details = listOf("discharge disabled"))).let { it.title to it.text })
        assertEquals("Высокая скорость" to "Bike: 48 км/ч",
            ru(m(AlertKind.SPEED_HIGH, 48.28f)).let { it.title to it.text })
        assertEquals("Высокая скорость" to "Bike: 30 миль/ч",
            ru(m(AlertKind.SPEED_HIGH, 48.28f), UnitSystem.IMPERIAL).let { it.title to it.text })
    }

    @Test
    fun `english through the real resources matches the stub for every kind`() {
        for (kind in AlertKind.entries) {
            val message = m(kind, 42f, listOf("F1", "F2"))
            assertEquals(
                AlertNotificationFormatter.format(message, UnitSystem.METRIC, TestAlertStrings),
                text(message, en),
                "$kind"
            )
        }
    }
}
