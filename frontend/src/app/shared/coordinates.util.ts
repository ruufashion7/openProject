function formatDmsComponent(value: number, isLatitude: boolean): string {
  const abs = Math.abs(value);
  const degrees = Math.floor(abs);
  const minutesFloat = (abs - degrees) * 60;
  const minutes = Math.floor(minutesFloat);
  const seconds = (minutesFloat - minutes) * 60;
  const direction = isLatitude ? (value >= 0 ? 'N' : 'S') : (value >= 0 ? 'E' : 'W');
  return `${degrees}°${minutes}'${seconds.toFixed(1)}"${direction}`;
}

/** e.g. 19°22'49.8"N 72°49'43.5"E */
export function formatCoordinatesDms(latitude: number, longitude: number): string {
  return `${formatDmsComponent(latitude, true)} ${formatDmsComponent(longitude, false)}`;
}
