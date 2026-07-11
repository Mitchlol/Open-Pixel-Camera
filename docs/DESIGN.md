# OpenPixelCamera — Design

## Concept

A video recording app that produces real-time light trail effects from the camera feed.

## Core Mechanism

The camera shutter speed is forced to `1 / framerate`, so the shutter stays fully open for each frame. This captures continuous light motion with no gaps between frames.

Default: 30fps (hardcoded). Adjustable framerate is a stretch goal.

## Controls

### ISO
- Adjusts sensor sensitivity to prevent overexposure.
- Auto ISO mode available.

### Trails Threshold
- Luma-key effect: pixels brighter than the threshold are used for trail generation.
- Acts as a sensitivity control for which pixels "count" as light trails.

### Trail Length
- Controls how many subsequent frames a bright pixel persists across.
- Example: threshold at 50%, trail length of 3 frames:
  - Frame 1: pixels above 50% brightness are saved.
  - Frame 2: shows its own image + bright pixels from frame 1.
  - Frame 3: shows its own image + bright pixels from frames 2 and 1.
  - Frame 4: shows its own image + bright pixels from frames 3, 2, and 1.
- Each frame's bright pixels are carried forward into the next N frames, layering on top of each other.

### Trail Fade (stretch goal)
- Boolean toggle. When enabled, trail pixels fade from 100% alpha (most recent frame) to 0% alpha (least recent frame).
