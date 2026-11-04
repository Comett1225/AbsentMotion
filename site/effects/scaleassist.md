Scale Assist adds extra scale on top of the layer's own size. Layers that have this layer as their parent follow the new size.

- **Axis**: **X** stretches the width, **Y** the height and **X,Y** both. **X,-Y** (the default) stretches the width while the height shrinks, for a squash and stretch.
- **Scale** is the extra scale: 100 % changes nothing.
- **Damping** (only for X,Y and X,-Y) adjusts the height. With X,Y the height is scaled by Scale times Damping; with X,-Y it shrinks by Damping times as much as the width grows.
