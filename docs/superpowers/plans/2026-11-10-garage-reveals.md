# Garage and recorded ride reveals

- Animate the transition from saved vehicle metadata to recorded charge and stats. Keep existing controls available and preserve vehicle identity through lazy layout updates.
- Reveal a newly decoded route snapshot in the existing preview slot. Keep cached previews visible when returning to the same card.
- Reveal the recorded ride list and the batch of older totals independently, with stable rows and no placeholder numbers.
- Trace cold Garage presence from BLE discovery through exact saved-address matching. Fix a reproducible source of false presence if found.
- Run the Android unit suite and record the cold Garage and vehicle overview on the emulator.
