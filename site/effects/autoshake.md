Auto-Shake moves the layer in random directions, over and over, without any keyframes. **Magnitude** is how far it moves, **Frequency** how many shakes per second, and **Angle** the main direction. **Seed** and **Evolution** change the random pattern, and **Z Shake** adds shaking in depth.

- Camera shake: Magnitude 15 to 30, Frequency 6 to 10 on a group that holds the whole scene.
- Impact: animate Magnitude from a large value down to 0 over half a second.
- Add [Motion Blur](@effect/motionblur) after it to blur the shaking the way a camera would.
