# Easing behaviour data

Numbers measured from the other app's keyframe easing, used by `tools/easing_check.py` to check
`app/src/main/cpp/easing.cpp`. Only the easing strings of the project file are shared with that
app; the curve formulas in `easing.cpp` were fitted to these numbers.

- `strings.json`: preset name to easing string, e.g. `"bounce": "bounce 0.3 0.8"`.
- `samples.json`: preset name to `[[progress, eased], ...]`, read off the app's own value display
  (two decimals of a 0..10000 travel, so good to about 1e-6). The check fails above 2e-5.
- `probe/NAME.json`: easing string to `[[progress, eased], ...]`, read off screenshots of a 300 px
  travel (good to about 5e-5, progress to about 1e-4). `probe/NAME.variants.json` lists the
  strings that were probed. The check fails above 5e-4.

Run from the project root: `python3 tools/easing_check.py` and `python3 tools/easing_check.py --probe`.

## What the data showed

u is time progress, x = u / a where a curve has a length a.

- **cubicBezier x1 y1 x2 y2**: CSS curve; solve x(s) = u by Newton steps from s = u that stop
  once x is within 1e-5 (that early stop shows in the easeIn preset), return y(s).
- **bounce L b**: drop (u / (L/2))^2, then parabolic bounces k = 1, 2, ... of height b^k and
  length L b^k. Bounces that would end after u = 1, or are lower than about 0.005, are not drawn.
- **elastic a b c d**: ring(t) = 1 - d |1 - t|^(1 + 15 c^2) cos(pi t / a). For u >= a it is
  ring(u); before that x^3 ring(u) + (1 - x^3) ring(a) (1 - cos(pi x)) / 2. b had no effect.
- **cyclic a b c d e**: period a; inside a period with phase f, s = f / c rising, (1 - f) / (1 - c)
  falling; wave = (1 - b)(1 - cos(pi s)) / 2 + b s; result d u + (1 - d u) wave. e had no effect.
- **random f m seed atten warp tilt**: line = u^k or 1 - (1 - u)^k (warp below / above 0.5),
  k = 1 + 10 |warp - 0.5|; trend = line + tilt (1 - 2 line); noise = n(30 f u, 10 seed) +
  0.5 n(60 f u, 10 seed + 23453.234534) with 2-D simplex noise n; result
  trend + m (1 - |1 - 2u|^3) (min(1, 2 - 2 atten) u + min(1, 2 atten)(1 - u))^2 noise.
- **step L s**: stairs L floor(u / L); the last s L of each stair rises with smoothstep.
- **elasticStep L m**: stair k >= 1 is L (k - 1 + elastic(s)) with s = u / L - k and the elastic
  curve above using a = 0.5 - 0.45 m, c = d = 1 - m / 2; the first stair is 0.

Not covered by any measurement: random with magnitude above 1, random with tilt and warp both
away from neutral, elastic b, cyclic e, the bounce cut-off height (only bracketed, 1/256 to 0.0059),
and anything with u outside 0..1.
