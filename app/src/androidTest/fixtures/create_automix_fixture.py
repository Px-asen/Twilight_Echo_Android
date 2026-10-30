"""Create a controlled musical decay from the existing generated test tone.

Requires numpy and soundfile. This tests real model/selection/transport plumbing,
not perceptual transition quality on commercial music. The unchanged tone has
no beat or decay and was correctly rejected with low_beat_confidence.
"""
from pathlib import Path
import numpy as np
import soundfile as sf

assets = Path(__file__).resolve().parent.parent / "assets"
samples, rate = sf.read(assets / "music/01-Golden-Hour.flac", dtype="float64", always_2d=True)
time = np.arange(len(samples)) / rate
# Continuous 3 dB/s release over the final eight seconds, with no trimmed audio.
gain = 10 ** (-3 * np.maximum(0, time - (len(samples) / rate - 8)) / 20)
target = assets / "automix/release.flac"
target.parent.mkdir(exist_ok=True)
sf.write(target, samples * gain[:, None], rate, subtype="PCM_16")
print(f"{target}: {len(samples) / rate:.3f}s, {rate}Hz, {samples.shape[1]}ch")
long_samples = np.tile(samples, (2, 1))
time = np.arange(len(long_samples)) / rate
gain = 10 ** (-3 * np.maximum(0, time - (len(long_samples) / rate - 8)) / 20)
sf.write(assets / "automix/release-long.flac", long_samples * gain[:, None], rate, subtype="PCM_16")
