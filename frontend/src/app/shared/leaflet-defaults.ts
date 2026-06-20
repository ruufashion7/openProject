import * as L from 'leaflet';

let configured = false;

/** Leaflet default marker URLs break under Angular bundling — point at copied assets. */
export function configureLeafletDefaults(): void {
  if (configured) {
    return;
  }
  configured = true;

  const iconDefault = L.Icon.Default.prototype as L.Icon.Default & {
    _getIconUrl?: unknown;
  };
  delete iconDefault._getIconUrl;

  L.Icon.Default.mergeOptions({
    iconRetinaUrl: 'assets/leaflet/marker-icon-2x.png',
    iconUrl: 'assets/leaflet/marker-icon.png',
    shadowUrl: 'assets/leaflet/marker-shadow.png',
  });
}
