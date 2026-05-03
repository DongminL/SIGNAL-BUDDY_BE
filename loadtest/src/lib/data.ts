export const SEED_FEEDBACK_COUNT = 4000;

// Rough bounding box for central Seoul (Gangnam to Jongno)
const LAT_MIN = 37.46;
const LAT_MAX = 37.64;
const LNG_MIN = 126.90;
const LNG_MAX = 127.10;

function hash(a: number, b: number): number {
  return ((a * 997 + b * 31) >>> 0);
}

export function feedbackId(vu: number, iter: number): number {
  return (hash(vu, iter) % SEED_FEEDBACK_COUNT) + 1;
}

export function latLng(vu: number, iter: number): { lat: number; lng: number } {
  const idx = hash(vu, iter) % 4000;
  const lat = LAT_MIN + (idx / 4000) * (LAT_MAX - LAT_MIN);
  const lng = LNG_MIN + (idx / 4000) * (LNG_MAX - LNG_MIN);
  return {
    lat: parseFloat(lat.toFixed(6)),
    lng: parseFloat(lng.toFixed(6)),
  };
}
