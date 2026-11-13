# OffconAI Design Direction

## Direction

- **Product.** A private, on-device AI workspace for Android and desktop. It runs the model, the documents and the voice on hardware the user owns.
- **Character.** Calm, exact, quietly technical.
- **World.** Graphite and ink instrument panels, off-white paper, one cool cobalt signal.
- **Moments.** The local-engine readout (model · runtime · quantization · context · tokens/sec) is the single rich moment on each screen.
- **Signature.** A small square status dot next to a monospace runtime line, used consistently across chat, drawer and settings.

## Rules

- One accent per screen: primary action, selection or live status. Everything else comes from the neutral ramp.
- No hue gradients, gradient text, colored glows, radial color washes or floating blobs.
- Surfaces are flat with a 1dp hairline. Depth comes from surface steps, not shadows.
- Spacing follows the 4px grid; controls are 40–44dp, cards use 10–16dp radii, and nested radii subtract padding.
- Type hierarchy comes from size and color. Weights stop at 500, with 600 reserved for rare emphasis.
- One line-icon set at 16–18dp. Icons take the muted or secondary text role, never an accent of their own.
- Keep motion for state changes only: short ease-out fades, no looping glows or bouncy springs.
- Numbers use the monospace family and one format. Runtime metrics read like an instrument, not a badge.
- Copy names what the product actually does. No greeting headlines, emoji labels, hype words or unverifiable claims.

The un-slopping pass follows the [unslop](https://github.com/heydamir/unslop) method by Damir Sokolovsky, licensed CC BY 4.0.
