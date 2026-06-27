import { Component, EventEmitter, Input, Output, OnInit, OnChanges, SimpleChanges, ViewChild, ElementRef, AfterViewInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import * as L from 'leaflet';
import { configureLeafletDefaults } from '../leaflet-defaults';

configureLeafletDefaults();

export interface LocationData {
  address: string;
  latitude: number;
  longitude: number;
}

@Component({
  selector: 'app-location-input',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './location-input.component.html',
  styleUrl: './location-input.component.css'
})
export class LocationInputComponent implements OnInit, OnChanges, AfterViewInit, OnDestroy {
  @Input() initialAddress: string = '';
  @Input() initialLatitude: number | null = null;
  @Input() initialLongitude: number | null = null;
  @Output() locationSelected = new EventEmitter<LocationData>();
  @Output() cancelled = new EventEmitter<void>();
  @Output() saveFailed = new EventEmitter<string>();

  @ViewChild('mapContainer', { static: false }) mapContainer!: ElementRef;

  address: string = '';
  latitude: number | null = null;
  longitude: number | null = null;
  showMapPicker: boolean = false;
  map: L.Map | null = null;
  marker: L.Marker | null = null;
  isGeocoding: boolean = false;
  searchQuery: string = '';
  searchResults: any[] = [];
  addressSuggestions: any[] = [];
  showAddressSuggestions: boolean = false;
  private addressSearchTimer: any = null;
  private searchQueryTimer: any = null;

  private readonly NOMINATIM_URL = 'https://nominatim.openstreetmap.org';

  constructor() {}

  ngOnInit(): void {
    this.applyInitialValues();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (
      changes['initialAddress'] ||
      changes['initialLatitude'] ||
      changes['initialLongitude']
    ) {
      this.applyInitialValues();
    }
  }

  private applyInitialValues(): void {
    this.address = this.initialAddress || '';
    this.latitude = this.initialLatitude;
    this.longitude = this.initialLongitude;
    this.searchQuery = '';
    this.searchResults = [];
    this.addressSuggestions = [];
    this.showAddressSuggestions = false;
  }

  ngAfterViewInit(): void {
    // Map will be initialized when map picker opens
  }

  ngOnDestroy(): void {
    if (this.addressSearchTimer) {
      clearTimeout(this.addressSearchTimer);
    }
    if (this.searchQueryTimer) {
      clearTimeout(this.searchQueryTimer);
    }
    if (this.map) {
      this.map.remove();
      this.map = null;
    }
  }

  openMapPicker(): void {
    this.showMapPicker = true;
    setTimeout(() => {
      this.initMap();
    }, 100);
  }

  closeMapPicker(): void {
    this.showMapPicker = false;
    if (this.map) {
      this.map.remove();
      this.map = null;
      this.marker = null;
    }
  }

  initMap(): void {
    if (!this.mapContainer || this.map) return;

    const center: [number, number] = this.latitude && this.longitude
      ? [this.latitude, this.longitude]
      : [28.6139, 77.2090]; // Default to Delhi

    this.map = L.map(this.mapContainer.nativeElement).setView(center, 12);

    // Add OpenStreetMap tiles
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© OpenStreetMap contributors',
      maxZoom: 19
    }).addTo(this.map);

    // Add marker if location exists
    if (this.latitude && this.longitude) {
      this.addMarker([this.latitude, this.longitude]);
    }

    // Handle map clicks
    this.map.on('click', (e: L.LeafletMouseEvent) => {
      this.setLocation(e.latlng.lat, e.latlng.lng);
    });
  }

  addMarker(latLng: [number, number]): void {
    if (!this.map) return;

    if (this.marker) {
      this.marker.setLatLng(latLng);
    } else {
      this.marker = L.marker(latLng, { draggable: true }).addTo(this.map);
      this.marker.on('dragend', (e: L.DragEndEvent) => {
        const position = this.marker!.getLatLng();
        this.setLocation(position.lat, position.lng);
      });
    }
    this.map.setView(latLng, this.map.getZoom());
  }

  setLocation(lat: number, lng: number, address?: string): void {
    this.latitude = lat;
    this.longitude = lng;

    if (this.map) {
      this.addMarker([lat, lng]);
    }

    if (address) {
      this.address = address;
    } else {
      this.address = this.formatCoordinates(lat, lng);
      void this.reverseGeocode(lat, lng);
    }
  }

  canSaveLocation(): boolean {
    const hasCoords = this.latitude !== null && this.longitude !== null;
    const hasAddress = !!this.address.trim();
    return hasCoords || hasAddress;
  }

  private formatCoordinates(lat: number, lng: number): string {
    return `${lat.toFixed(6)}, ${lng.toFixed(6)}`;
  }

  private parseCoordinateQuery(query: string): { lat: number; lng: number } | null {
    const trimmed = query.trim();
    if (!trimmed) {
      return null;
    }

    const decimalPair = trimmed.match(
      /^(-?\d+(?:\.\d+)?)\s*[,;\s]\s*(-?\d+(?:\.\d+)?)$/
    );
    if (decimalPair) {
      const lat = parseFloat(decimalPair[1]);
      const lng = parseFloat(decimalPair[2]);
      if (Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180) {
        return { lat, lng };
      }
    }

    const dmsPart =
      /(\d+(?:\.\d+)?)\s*°\s*(\d+(?:\.\d+)?)?['′]?\s*(\d+(?:\.\d+)?)?["″]?\s*([NnSsEeWw])/g;
    const parts = [...trimmed.matchAll(dmsPart)];
    if (parts.length < 2) {
      return null;
    }

    const toDecimal = (match: RegExpMatchArray, kind: 'lat' | 'lng'): number | null => {
      const direction = match[4].toUpperCase();
      if (kind === 'lat' && direction !== 'N' && direction !== 'S') {
        return null;
      }
      if (kind === 'lng' && direction !== 'E' && direction !== 'W') {
        return null;
      }

      const degrees = parseFloat(match[1]);
      const minutes = parseFloat(match[2] || '0');
      const seconds = parseFloat(match[3] || '0');
      let value = degrees + minutes / 60 + seconds / 3600;
      if (direction === 'S' || direction === 'W') {
        value *= -1;
      }
      return value;
    };

    const lat = toDecimal(parts[0], 'lat');
    const lng = toDecimal(parts[1], 'lng');
    if (
      lat === null ||
      lng === null ||
      !Number.isFinite(lat) ||
      !Number.isFinite(lng) ||
      Math.abs(lat) > 90 ||
      Math.abs(lng) > 180
    ) {
      return null;
    }

    return { lat, lng };
  }

  async reverseGeocode(lat: number, lng: number): Promise<void> {
    this.isGeocoding = true;
    try {
      const response = await fetch(
        `${this.NOMINATIM_URL}/reverse?format=json&lat=${lat}&lon=${lng}&zoom=18&addressdetails=1`
      );
      if (!response.ok) {
        return;
      }
      const data = await response.json();
      if (data?.display_name) {
        this.address = data.display_name;
      }
    } catch {
      // Keep coordinate fallback already set in setLocation
    } finally {
      this.isGeocoding = false;
    }
  }

  async searchAddress(): Promise<void> {
    if (!this.searchQuery.trim()) {
      this.searchResults = [];
      return;
    }

    const parsed = this.parseCoordinateQuery(this.searchQuery);
    if (parsed) {
      this.setLocation(parsed.lat, parsed.lng);
      this.searchResults = [];
      return;
    }

    this.isGeocoding = true;
    try {
      const response = await fetch(
        `${this.NOMINATIM_URL}/search?format=json&q=${encodeURIComponent(this.searchQuery)}&limit=5&addressdetails=1`
      );
      if (!response.ok) {
        this.searchResults = [];
        return;
      }
      const data = await response.json();
      this.searchResults = data || [];
    } catch {
      this.searchResults = [];
    } finally {
      this.isGeocoding = false;
    }
  }

  selectSearchResult(result: any): void {
    const lat = parseFloat(result.lat);
    const lng = parseFloat(result.lon);
    this.setLocation(lat, lng, result.display_name);
    this.searchQuery = '';
    this.searchResults = [];
  }

  async geocodeAddress(): Promise<void> {
    if (!this.address.trim()) return;
    
    this.isGeocoding = true;
    this.showAddressSuggestions = false;
    try {
      const response = await fetch(
        `${this.NOMINATIM_URL}/search?format=json&q=${encodeURIComponent(this.address)}&limit=1&addressdetails=1`
      );
      if (!response.ok) {
        return;
      }
      const data = await response.json();
      if (data && data.length > 0) {
        const result = data[0];
        const lat = parseFloat(result.lat);
        const lng = parseFloat(result.lon);
        this.setLocation(lat, lng, result.display_name);
      }
    } catch (error) {
      // Geocoding failed
    } finally {
      this.isGeocoding = false;
    }
  }

  onAddressInput(): void {
    // Clear previous timer
    if (this.addressSearchTimer) {
      clearTimeout(this.addressSearchTimer);
    }

    // If address is empty, clear suggestions
    if (!this.address.trim()) {
      this.addressSuggestions = [];
      this.showAddressSuggestions = false;
      return;
    }

    // Debounce search
    this.addressSearchTimer = setTimeout(() => {
      this.searchAddressSuggestions();
    }, 300);
  }

  async searchAddressSuggestions(): Promise<void> {
    if (!this.address.trim()) {
      this.addressSuggestions = [];
      this.showAddressSuggestions = false;
      return;
    }

    this.isGeocoding = true;
    try {
      const response = await fetch(
        `${this.NOMINATIM_URL}/search?format=json&q=${encodeURIComponent(this.address)}&limit=5&addressdetails=1`
      );
      if (!response.ok) {
        this.addressSuggestions = [];
        this.showAddressSuggestions = false;
        return;
      }
      const data = await response.json();
      this.addressSuggestions = data || [];
      this.showAddressSuggestions = this.addressSuggestions.length > 0;
    } catch (error) {
      // Address search failed
      this.addressSuggestions = [];
      this.showAddressSuggestions = false;
    } finally {
      this.isGeocoding = false;
    }
  }

  selectAddressSuggestion(suggestion: any): void {
    const lat = parseFloat(suggestion.lat);
    const lng = parseFloat(suggestion.lon);
    this.address = suggestion.display_name;
    this.setLocation(lat, lng, suggestion.display_name);
    this.addressSuggestions = [];
    this.showAddressSuggestions = false;
  }

  onAddressBlur(): void {
    // Delay hiding suggestions to allow click on suggestion
    setTimeout(() => {
      this.showAddressSuggestions = false;
    }, 200);
  }

  hasLocationData(): boolean {
    // Show save button if:
    // 1. There's current location data (address or coordinates)
    // 2. OR we're in edit mode (have initial values)
    const hasCurrentData = !!(this.address.trim() || (this.latitude !== null && this.longitude !== null));
    const isEditMode = !!(this.initialAddress || (this.initialLatitude !== null && this.initialLongitude !== null));
    return hasCurrentData || isEditMode;
  }

  useCurrentLocation(): void {
    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (position) => {
          this.setLocation(
            position.coords.latitude,
            position.coords.longitude
          );
        },
        () => {
          alert('Unable to get your location. Please enable location services.');
        }
      );
    } else {
      alert('Geolocation is not supported by your browser.');
    }
  }

  async saveLocation(): Promise<void> {
    if (!this.canSaveLocation() && !this.hasLocationData()) {
      return;
    }

    if (this.address.trim() && (this.latitude === null || this.longitude === null)) {
      await this.geocodeAddress();
    }

    if (this.latitude !== null && this.longitude !== null && !this.address.trim()) {
      await this.reverseGeocode(this.latitude, this.longitude);
      if (!this.address.trim()) {
        this.address = this.formatCoordinates(this.latitude, this.longitude);
      }
    }

    if (this.latitude === null || this.longitude === null) {
      this.saveFailed.emit(
        this.address.trim()
          ? 'Could not find coordinates for this address. Pick a suggestion, use the map, or try a different address.'
          : 'Please enter an address or pick a location on the map.'
      );
      return;
    }

    this.locationSelected.emit({
      address: this.address || this.formatCoordinates(this.latitude, this.longitude),
      latitude: this.latitude,
      longitude: this.longitude
    });

    this.closeMapPicker();
    this.addressSuggestions = [];
    this.showAddressSuggestions = false;
  }

  cancel(): void {
    // Reset to initial values
    this.address = this.initialAddress || '';
    this.latitude = this.initialLatitude;
    this.longitude = this.initialLongitude;
    this.cancelled.emit();
    this.closeMapPicker();
  }

  onSearchKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      void this.searchAddress().then(() => {
        if (this.searchResults.length > 0) {
          this.selectSearchResult(this.searchResults[0]);
        }
      });
    }
  }

  onSearchInput(): void {
    if (this.searchQueryTimer) {
      clearTimeout(this.searchQueryTimer);
    }

    if (!this.searchQuery.trim()) {
      this.searchResults = [];
      return;
    }

    this.searchQueryTimer = setTimeout(() => {
      if (this.searchQuery.trim()) {
        void this.searchAddress();
      }
    }, 500);
  }
}
