package ru.sodovaya.volty.presentation.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NearbyAuthValidationTest {
    @Test
    fun registration_rejects_a_password_the_server_will_reject() {
        assertEquals(
            "Пароль должен содержать от 12 до 128 символов",
            validateAuthForm(
                mode = NearbyComponent.AuthMode.REGISTER,
                email = "rider@example.com",
                password = "short",
                displayName = "Rider",
            ),
        )
    }

    @Test
    fun login_accepts_the_same_password_length_without_client_side_registration_rules() {
        assertNull(
            validateAuthForm(
                mode = NearbyComponent.AuthMode.LOGIN,
                email = "rider@example.com",
                password = "short",
                displayName = "",
            ),
        )
    }
}
