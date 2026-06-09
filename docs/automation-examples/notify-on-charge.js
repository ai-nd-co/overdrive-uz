// Tell me when charging starts and stops, and close the sunroof when charging begins
// (a hot parked car often gets left with the roof cracked).
scenario({
  when: "charge.start",
  run: function (ctx) {
    vehicle.sunroof("close");
    notify("Charging started; closing sunroof");
  }
});

scenario({
  when: "charge.stop",
  run: function (ctx) {
    notify("Charging stopped");
  }
});
