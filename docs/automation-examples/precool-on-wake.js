// Pre-cool the cabin when the car wakes up (ACC on), but only if there is enough charge.
// Demonstrates reading `state` to gate an action.
// NOTE: state.soc arrives in P3. In P1 only state.accOn and state.sentry are populated, so
// `state.soc > 30` is currently false (undefined) and this scenario no-ops until P3 lands.
scenario({
  when: "vehicle.wake",
  run: function (ctx) {
    if (state.soc > 30) {
      vehicle.climateOn();
      notify("Pre-cooling; SOC " + state.soc + "%");
    } else {
      log("Skipped pre-cool, low SOC: " + state.soc);
    }
  }
});
