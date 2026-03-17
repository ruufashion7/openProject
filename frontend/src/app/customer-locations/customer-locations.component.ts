import { Component, OnInit, OnDestroy, ViewChild, ElementRef, AfterViewInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import * as L from 'leaflet';
import { ApiService, CustomerLocation } from '../services/api.service';
import { ZoomableImageComponent } from '../shared/zoomable-image/zoomable-image.component';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';

interface CustomerMarker {
  customer: CustomerLocation;
  marker: L.Marker;
}

@Component({
  selector: 'app-customer-locations',
  standalone: true,
  imports: [CommonModule, ZoomableImageComponent],
  templateUrl: './customer-locations.component.html',
  styleUrl: './customer-locations.component.css'
})
export class CustomerLocationsComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('mapContainer', { static: false }) mapContainer!: ElementRef;

  status: 'idle' | 'loading' | 'failed' = 'loading';
  message = '';
  customers: CustomerLocation[] = [];
  markers: CustomerMarker[] = [];
  selectedMarker: CustomerMarker | null = null;
  map: L.Map | null = null;
  searchQuery: string = '';
  filteredCustomers: CustomerLocation[] = [];
  popup: L.Popup | null = null;

  constructor(
    private api: ApiService,
    private auth: AuthService,
    private permissionService: PermissionService,
    private router: Router,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    // Check permission
    if (!this.permissionService.canAccessCustomerLocations()) {
      this.status = 'failed';
      this.message = 'You do not have permission to access this page.';
      return;
    }

    // Make function available globally for popup buttons
    window.openCustomerDetails = (customerName: string) => {
      this.router.navigateByUrl(`/outstanding?customer=${customerName}`);
    };
    this.loadCustomerLocations();
  }

  ngAfterViewInit(): void {
    // Map will be initialized after customers are loaded and view is updated
  }

  ngOnDestroy(): void {
    if (this.map) {
      this.map.remove();
      this.map = null;
    }
  }

  initMap(): void {
    if (!this.mapContainer || !this.mapContainer.nativeElement) {
      // Retry if container is not ready
      setTimeout(() => this.initMap(), 100);
      return;
    }

    if (this.map) {
      // Map already initialized, just update markers
      this.createMarkers();
      return;
    }

    const mapElement = this.mapContainer.nativeElement;
    
    // Check if element is visible and has dimensions
    if (mapElement.offsetWidth === 0 || mapElement.offsetHeight === 0) {
      // Retry after a short delay
      setTimeout(() => this.initMap(), 100);
      return;
    }

    try {
      const center: [number, number] = [28.6139, 77.2090]; // Default to Delhi
      this.map = L.map(mapElement, {
        zoomControl: true,
        attributionControl: true
      }).setView(center, 10);

      // Add OpenStreetMap tiles
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        maxZoom: 19
      }).addTo(this.map);

      // Invalidate size to ensure map renders correctly
      setTimeout(() => {
        if (this.map) {
          this.map.invalidateSize();
          // Create markers after map is ready
          if (this.customers.length > 0) {
            this.createMarkers();
          }
        }
      }, 100);
    } catch (error) {
      this.status = 'failed';
      this.message = 'Failed to initialize map. Please refresh the page.';
    }
  }

  loadCustomerLocations(): void {
    this.status = 'loading';
    this.api.getCustomerLocations().subscribe({
      next: (customers) => {
        this.customers = customers;
        this.filteredCustomers = customers;
        this.status = 'idle';
        this.message = customers.length > 0 ? '' : 'No customers with location data found.';
        
        // Force change detection to ensure view is updated
        this.cdr.detectChanges();
        
        // Initialize map after view is updated and customers are loaded
        if (customers.length > 0) {
          setTimeout(() => {
            this.initMap();
          }, 100);
        }
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.status = 'failed';
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        }
        this.status = 'failed';
        this.message = 'Unable to load customer locations.';
      }
    });
  }

  createMarkers(): void {
    if (!this.map) return;

    // Remove existing markers
    this.markers.forEach(m => {
      this.map!.removeLayer(m.marker);
    });
    this.markers = [];

    // Create new markers
    this.filteredCustomers
      .filter(customer => customer.latitude !== null && customer.longitude !== null)
      .forEach(customer => {
        const lat = customer.latitude!;
        const lng = customer.longitude!;

        // Create custom icon
        const customIcon = L.divIcon({
          className: 'custom-marker',
          html: `
            <div style="
              width: 40px;
              height: 40px;
              border-radius: 50%;
              background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
              border: 3px solid #fff;
              box-shadow: 0 4px 12px rgba(99, 102, 241, 0.4);
              display: flex;
              align-items: center;
              justify-content: center;
              color: #fff;
              font-weight: bold;
              font-size: 14px;
            ">
              📍
            </div>
          `,
          iconSize: [40, 40],
          iconAnchor: [20, 20]
        });

        const marker = L.marker([lat, lng], { icon: customIcon }).addTo(this.map!);

        // Create popup content
        const popupContent = `
          <div class="info-window-content">
            <h3 class="info-window-title">${this.escapeHtml(customer.customerName)}</h3>
            <div class="info-window-body">
              ${customer.phoneNumber ? `<div class="info-window-item"><strong>Phone:</strong> ${this.escapeHtml(customer.phoneNumber)}</div>` : ''}
              ${customer.address ? `<div class="info-window-item"><strong>Address:</strong> ${this.escapeHtml(customer.address)}</div>` : ''}
            </div>
            <div class="info-window-actions">
              <button class="btn-view-details" onclick="window.openCustomerDetails('${encodeURIComponent(customer.customerName)}')">
                View Details
              </button>
            </div>
          </div>
        `;

        marker.bindPopup(popupContent);
        marker.on('click', () => {
          this.selectedMarker = { customer, marker };
        });

        this.markers.push({ customer, marker });
      });

    // Fit map to show all markers
    if (this.markers.length > 0) {
      const group = new L.FeatureGroup(this.markers.map(m => m.marker));
      this.map.fitBounds(group.getBounds().pad(0.1));
    }
  }

  escapeHtml(text: string): string {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  onSearchChange(query: string): void {
    this.searchQuery = query;
    const lowerQuery = query.toLowerCase().trim();
    
    if (!lowerQuery) {
      this.filteredCustomers = this.customers;
    } else {
      this.filteredCustomers = this.customers.filter(customer => {
        const nameMatch = customer.customerName?.toLowerCase().includes(lowerQuery);
        const phoneMatch = customer.phoneNumber?.toLowerCase().includes(lowerQuery);
        const addressMatch = customer.address?.toLowerCase().includes(lowerQuery);
        return nameMatch || phoneMatch || addressMatch;
      });
    }
    
    this.createMarkers();
  }

  clearSearch(): void {
    this.searchQuery = '';
    this.filteredCustomers = this.customers;
    this.createMarkers();
  }

  openCustomerDetails(customer: CustomerLocation): void {
    // SECURITY: Do NOT put sensitive data (customer names) in URL query parameters
    // Store in sessionStorage instead
    if (customer.customerName) {
      sessionStorage.setItem('openProject.selectedCustomer', customer.customerName);
      this.router.navigateByUrl('/outstanding');
    }
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }
}

// Make function available globally for popup buttons
declare global {
  interface Window {
    openCustomerDetails: (customerName: string) => void;
  }
}
