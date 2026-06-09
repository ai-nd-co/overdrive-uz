// Close the windows and lock the car when it goes to sleep (ACC off / you walk away).
// Push to /data/local/tmp/overdrive/scenarios/ on the unit, then arm with the `enabled`
// (and optionally `live`) flag files. See docs/js-automation-design.md.
scenario({
  when: "vehicle.sleep",
  run: function (ctx) {
    vehicle.closeWindows();
    vehicle.lock();
    notify("Closed windows and locked after ACC off");
  }
});
