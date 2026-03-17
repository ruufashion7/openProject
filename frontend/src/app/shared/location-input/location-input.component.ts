import { Component, EventEmitter, Input, Output, OnInit, ViewChild, ElementRef, AfterViewInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import * as L from 'leaflet';

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
export class LocationInputComponent implements OnInit, AfterViewInit, OnDestroy {
  @Input() initialAddress: string = '';
  @Input() initialLatitude: number | null = null;
  @Input() initialLongitude: number | null = null;
  @Output() locationSelected = new EventEmitter<LocationData>();
  @Output() cancelled = new EventEmitter<void>();

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

  private readonly NOMINATIM_URL = 'https://nominatim.openstreetmap.org';

  constructor() {}

  ngOnInit(): void {
    this.address = this.initialAddress || '';
    this.latitude = this.initialLatitude;
    this.longitude = this.initialLongitude;
  }

  ngAfterViewInit(): void {
    // Map will be initialized when map picker opens
  }

  ngOnDestroy(): void {
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
      // Reverse geocode to get address
      this.reverseGeocode(lat, lng);
    }
  }

  async reverseGeocode(lat: number, lng: number): Promise<void> {
    this.isGeocoding = true;
    try {
      const response = await fetch(
        `${this.NOMINATIM_URL}/reverse?format=json&lat=${lat}&lon=${lng}&zoom=18&addressdetails=1`,
        {
          headers: {
            'User-Agent': 'OpenProject/1.0' // Required by Nominatim
          }
        }
      );
      const data = await response.json();
      if (data && data.display_name) {
        this.address = data.display_name;
      }
    } catch (error) {
      // Reverse geocoding failed
    } finally {
      this.isGeocoding = false;
    }
  }

  async searchAddress(): Promise<void> {
    if (!this.searchQuery.trim()) {
      this.searchResults = [];
      return;
    }

    this.isGeocoding = true;
    try {
      const response = await fetch(
        `${this.NOMINATIM_URL}/search?format=json&q=${encodeURIComponent(this.searchQuery)}&limit=5&addressdetails=1`,
        {
          headers: {
            'User-Agent': 'OpenProject/1.0'
          }
        }
      );
      const data = await response.json();
      this.searchResults = data || [];
    } catch (error) {
      // Geocoding failed
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
        `${this.NOMINATIM_URL}/search?format=json&q=${encodeURIComponent(this.address)}&limit=1&addressdetails=1`,
        {
          headers: {
            'User-Agent': 'OpenProject/1.0'
          }
        }
      );
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
        `${this.NOMINATIM_URL}/search?format=json&q=${encodeURIComponent(this.address)}&limit=5&addressdetails=1`,
        {
          headers: {
            'User-Agent': 'OpenProject/1.0'
          }
        }
      );
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
    if (!this.hasLocationData()) {
      return;
    }

    // If we have address but no coordinates, try to geocode first
    if (this.address.trim() && (this.latitude === null || this.longitude === null)) {
      await this.geocodeAddress();
    }

    // Emit location data - coordinates may be null if geocoding failed
    this.locationSelected.emit({
      address: this.address || '',
      latitude: this.latitude ?? 0,
      longitude: this.longitude ?? 0
    });

    // Cleanup
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
      this.searchAddress();
    }
  }

  onSearchInput(): void {
    // Debounce search
    if (this.searchQuery.trim()) {
      setTimeout(() => {
        if (this.searchQuery.trim()) {
          this.searchAddress();
        }
      }, 500);
    } else {
      this.searchResults = [];
    }
  }
}
