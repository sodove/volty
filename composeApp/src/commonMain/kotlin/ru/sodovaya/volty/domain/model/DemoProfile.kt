package ru.sodovaya.volty.domain.model

/**
 * Which synthetic vehicle "Try demo" brings up.
 *
 * The demo exists so somebody can see a dashboard without owning the hardware,
 * and the two shapes volty renders are genuinely different vehicles — not the
 * same curve under two names. A VESC scooter reports one pack behind one
 * controller and derives nothing; a Begode wheel is ONE BLE link owning one
 * controller and TWO parallel battery branches, reports ground speed and true
 * hardware PWM, carries a motor thermistor beside its board sensor, and reports
 * no energy counters at all. A rider evaluating volty for a wheel needs to see
 * the wheel's dashboard, and half the dials differ.
 *
 * Lives in `domain/model` rather than beside the simulator because
 * [ru.sodovaya.volty.domain.repository.BmsRepository.connectDemo] takes it —
 * the domain layer must not reach into `data/`.
 */
enum class DemoProfile {
    /** The original demo: a 16s VESC scooter, one pack, one controller. */
    SCOOTER,

    /** A Begode electric unicycle: one link, one controller, two 24s branches. */
    WHEEL
}
