import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService } from '../services/api.service';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
import * as XLSX from 'xlsx';
import jsPDF from 'jspdf';
import autoTable from 'jspdf-autotable';

// Watermark helper function
function addWatermark(doc: jsPDF): void {
  const pageWidth = doc.internal.pageSize.getWidth();
  const pageHeight = doc.internal.pageSize.getHeight();
  
  // Save current graphics state
  doc.saveGraphicsState();
  
  // Set watermark properties
  doc.setTextColor(200, 200, 200); // Light gray
  doc.setFontSize(60);
  doc.setFont('helvetica', 'bold');
  
  // Calculate center position
  const text = 'RUU FASHION';
  const textWidth = doc.getTextWidth(text);
  const x = (pageWidth - textWidth) / 2;
  const y = pageHeight / 2;
  
  // Rotate and draw watermark
  doc.setGState(doc.GState({ opacity: 0.15 })); // 15% opacity
  doc.text(text, x, y, {
    angle: 45,
    align: 'center'
  });
  
  // Restore graphics state
  doc.restoreGraphicsState();
}

export interface RateListEntry {
  id?: string;
  date: 'old' | 'new';
  type: 'landing' | 'resale';
  productName: string;
  size: '80-90' | '95-100';
  rate: number;
  srNo?: number; // Serial number for product ordering
  createdAt?: string;
}

@Component({
  selector: 'app-rate-list',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './rate-list.component.html',
  styleUrl: './rate-list.component.css'
})
export class RateListComponent implements OnInit {
  status: 'idle' | 'loading' | 'failed' = 'idle';
  message = '';
  
  // Form data
  formData: RateListEntry = {
    date: 'new',
    type: 'landing',
    productName: '',
    size: '80-90',
    rate: 0,
    srNo: undefined
  };
  
  // Edit state
  isEditing = false;
  editingEntry: RateListEntry | null = null;
  editFormData: RateListEntry = {
    date: 'new',
    type: 'landing',
    productName: '',
    size: '80-90',
    rate: 0
  };
  editProductSearchQuery = '';
  showEditProductDropdown = false;
  
  // Rate list entries
  rateListEntries: RateListEntry[] = [];
  filteredEntries: RateListEntry[] = [];
  private cachedGroupedEntries: Array<{
    productName: string;
    size: '80-90' | '95-100';
    srNo?: number;
    landingRate?: number;
    resaleRate?: number;
    landingEntry?: RateListEntry;
    resaleEntry?: RateListEntry;
  }> | null = null;
  
  // Filters
  dateFilter: 'all' | 'new' | 'old' = 'all';
  typeFilter: 'all' | 'landing' | 'resale' = 'all';
  
  // Search
  searchQuery = '';
  searchSuggestions: string[] = [];
  showSearchDropdown = false;
  
  // Product names (predefined + user added)
  productNames: string[] = [];
  productSearchQuery = '';
  filteredProductNames: string[] = [];
  showProductDropdown = false;
  
  // Product management
  showAddProductModal = false;
  newProductName = '';
  showDeleteProductModal = false;
  productToDelete = '';
  
  // Bulk upload
  showBulkUploadModal = false;
  selectedFile: File | null = null;
  uploadStatus: 'idle' | 'uploading' | 'success' | 'error' = 'idle';
  uploadMessage = '';
  uploadErrors: string[] = [];

  // srNo editing
  editingSrNoProduct: string | null = null;
  editingSrNoValue: number | null = null;
  showSrNoInput = false;

  canAccessRateList = false;
  canUploadRateListFiles = false;

  constructor(
    private api: ApiService,
    private auth: AuthService,
    private router: Router,
    public permissionService: PermissionService,
    private notificationService: NotificationService
  ) {}

  ngOnInit(): void {
    this.canAccessRateList = this.permissionService.canAccessRateList();
    this.canUploadRateListFiles = this.permissionService.canUploadRateListFiles();
    if (!this.canAccessRateList) {
      this.router.navigateByUrl('/welcome');
      return;
    }
    this.loadRateListEntries();
    this.loadProductNames();
  }

  loadRateListEntries(): void {
    // Don't set loading status if already loading to avoid conflicts
    if (this.status !== 'loading') {
      this.status = 'loading';
    }
    this.message = '';
    this.api.getRateListEntries().subscribe({
      next: (entries) => {
        this.rateListEntries = entries || [];
        this.cachedGroupedEntries = null; // Clear cache when data reloads
        this.applyFilters();
        this.status = 'idle';
      },
      error: (err: HttpErrorResponse) => {
        // Error loading rate list entries
        this.status = 'failed';
        if (err.status === 401) {
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        }
        if (err.status === 404) {
          this.message = 'Rate list endpoint not found. Please check backend configuration.';
        } else if (err.status === 0) {
          this.message = 'Cannot connect to server. Please check if the backend is running.';
        } else {
          this.message = `Failed to load rate list entries. ${err.error?.message || err.message || 'Unknown error'}`;
        }
      }
    });
  }

  loadProductNames(): void {
    // Predefined product names
    const predefinedProducts = [
      'rupa jon volt trunk',
      'rupa macroman',
      'rupa expando',
      'rupa hunk trunk',
      'rupa jon vest wht rn',
      'rupa jon vest wht rns',
      'rupa jon vest colour rn',
      'rupa frontline vest wht rn',
      'rupa frontline xing wht',
      'rupa frontline xing black',
      'rupa hunk gym vest #1061',
      'rupa hunk gym vest #072',
      'macho yellow trunk',
      'macho metro red cut',
      'macho green mini trunk',
      'macho blue intro long trunk',
      'macho metro vest red',
      'macho parker lining vest green',
      'speed trunk',
      'speed vest',
      'lux venus trunk',
      'lux venus trunk 95/100',
      'lux venus vest',
      'amul comfy fcd',
      'amul comfy rn wht 2/10',
      'amul comfy trunk',
      'amul comfy plain cycling short',
      'amul marvel kids trunk m9023',
      'amul marvel kids vest rn m9001',
      'amul sporto intro trunk',
      'amul sporto plain long trunk',
      'amul sporto smart cut brief',
      'amul sporto plain mini trunk',
      'amul sporto gym vest 111/222 (80/85/90/95/100)'
    ];

    // Load user-added products from localStorage
    const savedProducts = this.getSavedProducts();
    
    // Clean up old product names from localStorage
    const oldProductNames = [
      'jon volt trunk', 'macroman', 'expando', 'hunk trunk',
      'jon vest wht rn', 'jon vest wht rns', 'jon vest colour rn',
      'frontline vest wht rn', 'frontline xing wht', 'frontline xing black',
      'hunk gym vest #1061', 'hunk gym vest #072',
      'metro red cut',
      'marvel kids trunk m9023', 'marvel kids vest rn m9001',
      'sporto intro trunk', 'sporto plain long trunk',
      'sporto smart cut brief', 'sporto plain mini trunk',
      'sporto gym vest 111/222 (80/85/90/95/100)'
    ];
    
    // Remove old product names from saved products
    const cleanedSavedProducts = savedProducts.filter(
      product => !oldProductNames.includes(product.toLowerCase())
    );
    
    // If we removed old products, save the cleaned list
    if (cleanedSavedProducts.length !== savedProducts.length) {
      try {
        localStorage.setItem('rateListProducts', JSON.stringify(cleanedSavedProducts));
      } catch (error) {
        // Failed to clean up old products from localStorage
      }
    }
    
    // Merge predefined products with cleaned saved products
    const allProducts = [...new Set([...predefinedProducts, ...cleanedSavedProducts])];
    this.productNames = this.sortProductsByPriority(allProducts);
    this.filteredProductNames = this.productNames;

    // Also try to load from API and merge
    this.api.getCustomerSuggestions('', 1000).subscribe({
      next: (names) => {
        // Merge all sources, remove duplicates, and sort
        const allNames = [...new Set([...this.productNames, ...names])];
        this.productNames = this.sortProductsByPriority(allNames);
        this.filteredProductNames = this.productNames;
        this.saveProducts();
      },
      error: () => {
        // If API fails, use what we have
        this.saveProducts();
      }
    });
  }

  // Sort products by priority: rupa -> macho -> speed -> others
  sortProductsByPriority(products: string[]): string[] {
    return products.sort((a, b) => {
      const aPriority = this.getProductPriority(a);
      const bPriority = this.getProductPriority(b);
      
      if (aPriority !== bPriority) {
        return aPriority - bPriority;
      }
      
      // If same priority, sort alphabetically
      return a.localeCompare(b);
    });
  }

  // Get priority for product sorting: rupa=1, macho=2, speed=3, others=4
  getProductPriority(productName: string): number {
    const lowerName = productName.toLowerCase();
    if (lowerName.startsWith('rupa')) {
      return 1;
    } else if (lowerName.startsWith('macho')) {
      return 2;
    } else if (lowerName.startsWith('speed')) {
      return 3;
    }
    return 4;
  }

  getSavedProducts(): string[] {
    try {
      const saved = localStorage.getItem('rateListProducts');
      return saved ? JSON.parse(saved) : [];
    } catch {
      return [];
    }
  }

  saveProducts(): void {
    try {
      localStorage.setItem('rateListProducts', JSON.stringify(this.productNames));
    } catch (error) {
      // Failed to save products
    }
  }

  openAddProductModal(): void {
    this.newProductName = '';
    this.showAddProductModal = true;
  }

  closeAddProductModal(): void {
    this.showAddProductModal = false;
    this.newProductName = '';
  }

  addProduct(): void {
    if (!this.newProductName || this.newProductName.trim() === '') {
      return;
    }

    const productName = this.newProductName.trim();
    
    // Check if product already exists
    if (this.productNames.includes(productName)) {
      this.message = 'Product already exists!';
      this.status = 'failed';
      return;
    }

    // Add to list
    this.productNames.push(productName);
    this.productNames = this.sortProductsByPriority(this.productNames);
    this.filteredProductNames = this.productNames;
    this.saveProducts();

    // Set as selected in form
    this.formData.productName = productName;
    this.productSearchQuery = productName;

    this.closeAddProductModal();
    this.notificationService.showSuccess('Product added successfully!', 3000);
  }

  openDeleteProductModal(productName: string): void {
    // Check if product is used in any rate list entries
    const isUsed = this.rateListEntries.some(entry => entry.productName === productName);
    if (isUsed) {
      this.notificationService.showWarning('Cannot delete product that is used in rate list entries!', 5000);
      return;
    }

    this.productToDelete = productName;
    this.showDeleteProductModal = true;
  }

  closeDeleteProductModal(): void {
    this.showDeleteProductModal = false;
    this.productToDelete = '';
  }

  deleteProduct(): void {
    if (!this.productToDelete) {
      return;
    }

    // Remove from list
    this.productNames = this.productNames.filter(p => p !== this.productToDelete);
    this.filteredProductNames = this.productNames;
    this.saveProducts();

    // Clear form if deleted product was selected
    if (this.formData.productName === this.productToDelete) {
      this.formData.productName = '';
      this.productSearchQuery = '';
    }
    if (this.editFormData.productName === this.productToDelete) {
      this.editFormData.productName = '';
      this.editProductSearchQuery = '';
    }

    this.closeDeleteProductModal();
    this.notificationService.showSuccess('Product deleted successfully!', 3000);
  }

  clearProductSelection(): void {
    this.formData.productName = '';
    this.productSearchQuery = '';
  }

  clearEditProductSelection(): void {
    this.editFormData.productName = '';
    this.editProductSearchQuery = '';
  }

  onProductSearchChange(value: string): void {
    this.productSearchQuery = value;
    
    // Update formData only if it matches an existing product or is empty
    const exactMatch = this.productNames.find(p => p.toLowerCase() === value.toLowerCase());
    if (exactMatch) {
      this.formData.productName = exactMatch;
    } else if (value.trim().length === 0) {
      this.formData.productName = '';
    } else {
      // Allow free text entry for new products
      this.formData.productName = value;
    }
    
    if (value.trim().length === 0) {
      this.filteredProductNames = [];
      this.showProductDropdown = false;
      return;
    }
    
    // Show autocomplete only after 3+ characters
    if (value.trim().length < 3) {
      this.filteredProductNames = [];
      this.showProductDropdown = false;
      return;
    }
    
    const query = value.toLowerCase();
    this.filteredProductNames = this.productNames.filter(name =>
      name.toLowerCase().includes(query)
    );
    this.showProductDropdown = this.filteredProductNames.length > 0;
  }

  selectProduct(productName: string): void {
    this.formData.productName = productName;
    this.productSearchQuery = productName;
    this.showProductDropdown = false;
    this.filteredProductNames = [];
    // Trigger change detection
    setTimeout(() => {
      this.onProductSearchChange(productName);
    }, 0);
  }

  onProductInputBlur(): void {
    // Delay closing dropdown to allow click
    setTimeout(() => {
      this.showProductDropdown = false;
    }, 200);
  }

  onEditProductSearchChange(value: string): void {
    this.editProductSearchQuery = value;
    
    // Update editFormData only if it matches an existing product or is empty
    const exactMatch = this.productNames.find(p => p.toLowerCase() === value.toLowerCase());
    if (exactMatch) {
      this.editFormData.productName = exactMatch;
    } else if (value.trim().length === 0) {
      this.editFormData.productName = '';
    } else {
      // Allow free text entry for new products
      this.editFormData.productName = value;
    }
    
    if (value.trim().length === 0) {
      this.filteredProductNames = [];
      this.showEditProductDropdown = false;
      return;
    }
    
    // Show autocomplete only after 3+ characters
    if (value.trim().length < 3) {
      this.filteredProductNames = [];
      this.showEditProductDropdown = false;
      return;
    }
    
    const query = value.toLowerCase();
    this.filteredProductNames = this.productNames.filter(name =>
      name.toLowerCase().includes(query)
    );
    this.showEditProductDropdown = this.filteredProductNames.length > 0;
  }

  selectEditProduct(productName: string): void {
    this.editFormData.productName = productName;
    this.editProductSearchQuery = productName;
    this.showEditProductDropdown = false;
    this.filteredProductNames = [];
  }

  onEditProductInputBlur(): void {
    setTimeout(() => {
      this.showEditProductDropdown = false;
    }, 200);
  }

  submitForm(): void {
    if (!this.formData.productName || this.formData.productName.trim() === '') {
      this.message = 'Please select a product name.';
      this.status = 'failed';
      return;
    }

    if (this.formData.rate <= 0) {
      this.message = 'Please enter a valid rate (greater than 0).';
      this.status = 'failed';
      return;
    }

    // Trim product name before submission
    const entryToSubmit = {
      ...this.formData,
      productName: this.formData.productName.trim()
    };

    this.status = 'loading';
    this.message = '';

    this.api.createRateListEntry(entryToSubmit).subscribe({
      next: (entry) => {
        this.rateListEntries.unshift(entry);
        this.applyFilters();
        this.resetForm();
        this.status = 'idle';
        // Show success notification for 10 seconds
        this.notificationService.showSuccess('Data added successfully!', 10000);
      },
      error: (err: HttpErrorResponse) => {
        // Error creating rate list entry
        this.status = 'failed';
        if (err.status === 401) {
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        }
        if (err.status === 0) {
          this.message = 'Cannot connect to server. Please check if the backend is running.';
          this.notificationService.showError(this.message, 5000);
        } else if (err.status === 400) {
          // Bad request - validation error
          const errorMessage = err.error?.message || 'Invalid input. Please check all fields and try again.';
          this.message = errorMessage;
          this.notificationService.showError(errorMessage, 8000);
        } else if (err.status === 409) {
          // Conflict - duplicate entry
          const errorMessage = err.error?.message || 'Duplicate entry found! An entry with the same Product Name, Date, Type, and Size already exists. Please update the existing entry instead.';
          this.message = errorMessage;
          this.notificationService.showError(errorMessage, 10000);
        } else if (err.status === 500) {
          // Internal server error
          const errorMessage = err.error?.message || 'An error occurred while saving the entry. Please try again or contact support.';
          this.message = errorMessage;
          this.notificationService.showError(errorMessage, 8000);
        } else {
          const errorMessage = err.error?.message || `Failed to save rate list entry. ${err.message || ''}`;
          this.message = errorMessage;
          this.notificationService.showError(errorMessage, 5000);
        }
      }
    });
  }

  resetForm(): void {
    this.formData = {
      date: 'new',
      type: 'landing',
      productName: '',
      size: '80-90',
      rate: 0,
      srNo: undefined
    };
    this.productSearchQuery = '';
    this.showProductDropdown = false;
  }

  startEdit(entry: RateListEntry): void {
    if (!entry.id) return;
    
    this.editingEntry = entry;
    this.isEditing = true;
    this.editFormData = {
      date: entry.date,
      type: entry.type,
      productName: entry.productName,
      size: entry.size,
      rate: entry.rate
    };
    this.editProductSearchQuery = entry.productName;
    this.showEditProductDropdown = false;
  }

  cancelEdit(): void {
    this.isEditing = false;
    this.editingEntry = null;
    this.editFormData = {
      date: 'new',
      type: 'landing',
      productName: '',
      size: '80-90',
      rate: 0
    };
    this.editProductSearchQuery = '';
    this.showEditProductDropdown = false;
  }

  updateEntry(): void {
    if (!this.editingEntry || !this.editingEntry.id) {
      return;
    }

    if (!this.editFormData.productName || this.editFormData.productName.trim() === '') {
      this.message = 'Please select a product name.';
      this.status = 'failed';
      return;
    }

    if (this.editFormData.rate <= 0) {
      this.message = 'Please enter a valid rate (greater than 0).';
      this.status = 'failed';
      return;
    }

    this.status = 'loading';
    this.message = '';

    this.api.updateRateListEntry(this.editingEntry.id, this.editFormData).subscribe({
      next: (updatedEntry) => {
        // Update the entry in the list
        const index = this.rateListEntries.findIndex(e => e.id === updatedEntry.id);
        if (index !== -1) {
          this.rateListEntries[index] = updatedEntry;
        }
        this.applyFilters();
        this.cancelEdit();
        this.status = 'idle';
        // Show success notification for 10 seconds
        this.notificationService.showSuccess('Entry updated successfully!', 10000);
      },
      error: (err: HttpErrorResponse) => {
        // Error updating rate list entry
        this.status = 'failed';
        if (err.status === 401) {
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        }
        if (err.status === 0) {
          this.message = 'Cannot connect to server. Please check if the backend is running.';
        } else {
          this.message = err.error?.message || `Failed to update entry. ${err.message || ''}`;
        }
      }
    });
  }


  deleteEntry(entry: RateListEntry): void {
    if (!entry.id) return;
    
    if (!confirm(`Are you sure you want to delete this rate list entry?`)) {
      return;
    }

    this.api.deleteRateListEntry(entry.id).subscribe({
      next: () => {
        this.rateListEntries = this.rateListEntries.filter(e => e.id !== entry.id);
        this.applyFilters();
        // Show success notification for 10 seconds
        this.notificationService.showSuccess('Entry deleted successfully!', 10000);
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        }
        this.message = 'Failed to delete entry.';
      }
    });
  }

  applyFilters(): void {
    // Only show data if date filter is selected (type can be 'all' for both)
    if (this.dateFilter === 'all') {
      this.filteredEntries = [];
      this.cachedGroupedEntries = null; // Clear cache
      return;
    }

    let filtered = [...this.rateListEntries];

    // Date filter (New/Old) - required
    filtered = filtered.filter(entry => entry.date === this.dateFilter);

    // Type filter (Landing/Resale/Both) - if 'all', show both types
    if (this.typeFilter !== 'all') {
      filtered = filtered.filter(entry => entry.type === this.typeFilter);
    }

    // Search filter - search in product name, size, and rate
    if (this.searchQuery && this.searchQuery.trim().length > 0) {
      const query = this.searchQuery.trim().toLowerCase();
      filtered = filtered.filter(entry => {
        const productMatch = entry.productName.toLowerCase().includes(query);
        const sizeMatch = entry.size.toLowerCase().includes(query);
        const rateMatch = entry.rate.toString().includes(query);
        return productMatch || sizeMatch || rateMatch;
      });
    }

    this.filteredEntries = filtered;
    this.cachedGroupedEntries = null; // Clear cache when filters change
  }

  onSearchChange(value: string): void {
    this.searchQuery = value;
    
    // Generate suggestions from product names
    if (value.trim().length === 0) {
      this.searchSuggestions = [];
      this.showSearchDropdown = false;
      this.applyFilters();
      return;
    }
    
    // Show suggestions only after 2+ characters
    if (value.trim().length < 2) {
      this.searchSuggestions = [];
      this.showSearchDropdown = false;
      this.applyFilters();
      return;
    }
    
    const query = value.toLowerCase();
    // Get unique product names that match
    const matchingProducts = this.productNames.filter(name =>
      name.toLowerCase().includes(query)
    );
    
    // Also get matching sizes
    const matchingSizes = ['80-90', '95-100'].filter(size =>
      size.toLowerCase().includes(query)
    );
    
    // Combine and limit suggestions
    this.searchSuggestions = [...matchingProducts, ...matchingSizes].slice(0, 8);
    this.showSearchDropdown = this.searchSuggestions.length > 0;
    
    // Apply filters immediately for real-time search
    this.applyFilters();
  }

  selectSearchSuggestion(suggestion: string): void {
    this.searchQuery = suggestion;
    this.showSearchDropdown = false;
    this.applyFilters();
  }

  onSearchInputBlur(): void {
    setTimeout(() => {
      this.showSearchDropdown = false;
    }, 200);
  }

  clearSearch(): void {
    this.searchQuery = '';
    this.searchSuggestions = [];
    this.showSearchDropdown = false;
    this.applyFilters();
  }

  // Group entries by product name and size for table display
  // Cached to prevent multiple recalculations during change detection
  getGroupedEntries(): Array<{
    productName: string;
    size: '80-90' | '95-100';
    srNo?: number;
    landingRate?: number;
    resaleRate?: number;
    landingEntry?: RateListEntry;
    resaleEntry?: RateListEntry;
  }> {
    // Return cached result if available
    if (this.cachedGroupedEntries !== null) {
      return this.cachedGroupedEntries;
    }

    const grouped: { [key: string]: {
      productName: string;
      size: '80-90' | '95-100';
      srNo?: number;
      landingRate?: number;
      resaleRate?: number;
      landingEntry?: RateListEntry;
      resaleEntry?: RateListEntry;
    } } = {};

    // Process filtered entries
    this.filteredEntries.forEach(entry => {
      const key = `${entry.productName}-${entry.size}`;
      if (!grouped[key]) {
        grouped[key] = {
          productName: entry.productName,
          size: entry.size,
          srNo: entry.srNo
        };
      }
      
      // Update srNo if entry has one (should be same for all entries of same product)
      if (entry.srNo !== undefined && entry.srNo !== null) {
        grouped[key].srNo = entry.srNo;
      }
      
      if (entry.type === 'landing') {
        grouped[key].landingRate = entry.rate;
        grouped[key].landingEntry = entry;
      } else if (entry.type === 'resale') {
        grouped[key].resaleRate = entry.rate;
        grouped[key].resaleEntry = entry;
      }
    });

    // Convert to array and sort by srNo first (starting from 1), then by product priority, then by product name, then by size
    this.cachedGroupedEntries = Object.values(grouped).sort((a, b) => {
      // Sort by srNo first (products with srNo come first, sorted ascending)
      const aSrNo = a.srNo ?? Number.MAX_SAFE_INTEGER;
      const bSrNo = b.srNo ?? Number.MAX_SAFE_INTEGER;
      
      if (aSrNo !== bSrNo) {
        return aSrNo - bSrNo;
      }
      
      // If no srNo or same srNo, fall back to priority-based sorting
      const aPriority = this.getProductPriority(a.productName);
      const bPriority = this.getProductPriority(b.productName);
      
      if (aPriority !== bPriority) {
        return aPriority - bPriority;
      }
      
      // If same priority, sort alphabetically by product name
      if (a.productName !== b.productName) {
        return a.productName.localeCompare(b.productName);
      }
      
      // Then by size
      return a.size === '80-90' ? -1 : 1;
    });
    
    return this.cachedGroupedEntries;
  }

  // Get rowspan for product name (number of sizes for this product)
  getProductRowspan(productName: string): number {
    const sizes = new Set(
      this.filteredEntries
        .filter(e => e.productName === productName)
        .map(e => e.size)
    );
    return sizes.size;
  }

  // Check if this is the first row for a product
  isFirstRowForProduct(productName: string, size: '80-90' | '95-100', groupedEntries: Array<any>, index: number): boolean {
    if (index === 0) return true;
    const prevEntry = groupedEntries[index - 1];
    return prevEntry.productName !== productName;
  }

  clearFilters(): void {
    this.dateFilter = 'all';
    this.typeFilter = 'all';
    this.applyFilters();
  }

  downloadExcel(): void {
    const groupedEntries = this.getGroupedEntries();
    if (groupedEntries.length === 0) {
      return;
    }

    // Prepare headers based on type filter
    let headers: string[] = ['Sr No', 'Product Name', 'Size'];
    if (this.typeFilter === 'all' || this.typeFilter === 'landing') {
      headers.push('Landing');
    }
    if (this.typeFilter === 'all' || this.typeFilter === 'resale') {
      headers.push('Resale');
    }

    // Prepare table data
    const tableData = groupedEntries.map(group => {
      const row: any[] = [
        group.srNo ?? '',
        group.productName,
        group.size
      ];
      
      if (this.typeFilter === 'all' || this.typeFilter === 'landing') {
        row.push(group.landingRate !== undefined ? `₹${group.landingRate.toFixed(2)}` : '-');
      }
      if (this.typeFilter === 'all' || this.typeFilter === 'resale') {
        row.push(group.resaleRate !== undefined ? `₹${group.resaleRate.toFixed(2)}` : '-');
      }
      
      return row;
    });

    // Create workbook
    const wb = XLSX.utils.book_new();
    
    // Add watermark row at the top
    const watermarkRow: any[] = [];
    const totalCols = headers.length;
    for (let i = 0; i < totalCols; i++) {
      watermarkRow.push('');
    }
    // Set watermark text in the middle column(s)
    const midCol = Math.floor(totalCols / 2);
    watermarkRow[midCol] = 'RUU FASHION';
    
    // Combine watermark, headers and table data
    const allData = [watermarkRow, headers, ...tableData];
    const ws = XLSX.utils.aoa_to_sheet(allData);
    
    // Set column widths
    const colWidths = [
      { wch: 8 },  // Sr No
      { wch: 30 }, // Product Name
      { wch: 12 }, // Size
    ];
    if (this.typeFilter === 'all' || this.typeFilter === 'landing') {
      colWidths.push({ wch: 15 }); // Landing
    }
    if (this.typeFilter === 'all' || this.typeFilter === 'resale') {
      colWidths.push({ wch: 15 }); // Resale
    }
    ws['!cols'] = colWidths;
    
    // Style watermark row (row 0) - merge cells and center
    if (!ws['!merges']) {
      ws['!merges'] = [];
    }
    // Merge all cells in watermark row
    ws['!merges'].push({ s: { r: 0, c: 0 }, e: { r: 0, c: totalCols - 1 } });
    
    XLSX.utils.book_append_sheet(wb, ws, 'Rate List');

    // Generate filename
    const dateFilter = this.dateFilter === 'new' ? 'New' : 'Old';
    const typeFilter = this.typeFilter === 'all' ? 'All' : this.typeFilter === 'landing' ? 'Landing' : 'Resale';
    const filename = `Rate_List_${dateFilter}_${typeFilter}.xlsx`;
    
    // Download
    XLSX.writeFile(wb, filename);
  }

  downloadPDF(): void {
    const groupedEntries = this.getGroupedEntries();
    if (groupedEntries.length === 0) {
      return;
    }

    const doc = new jsPDF('landscape');
    let yPos = 15;

    // Title
    doc.setFontSize(16);
    doc.setFont('helvetica', 'bold');
    const dateFilter = this.dateFilter === 'new' ? 'New' : 'Old';
    const typeFilter = this.typeFilter === 'all' ? 'All Types' : this.typeFilter === 'landing' ? 'Landing' : 'Resale';
    doc.text(`Rate List - ${dateFilter} (${typeFilter})`, 14, yPos);
    yPos += 8;

    // Summary info
    doc.setFontSize(10);
    doc.setFont('helvetica', 'normal');
    const today = new Date();
    const dateStr = `${today.getDate().toString().padStart(2, '0')}-${(today.getMonth() + 1).toString().padStart(2, '0')}-${today.getFullYear()}`;
    doc.text(`Generated: ${dateStr} | Entries: ${groupedEntries.length}`, 14, yPos);
    yPos += 8;

    // Prepare table headers
    const headers: string[] = ['Product Name', 'Size'];
    if (this.typeFilter === 'all' || this.typeFilter === 'landing') {
      headers.push('Landing');
    }
    if (this.typeFilter === 'all' || this.typeFilter === 'resale') {
      headers.push('Resale');
    }

    // Prepare table data
    const tableData = groupedEntries.map(group => {
      const row: any[] = [
        group.productName,
        group.size
      ];
      
      if (this.typeFilter === 'all' || this.typeFilter === 'landing') {
        row.push(group.landingRate !== undefined ? `₹${group.landingRate.toFixed(2)}` : '-');
      }
      if (this.typeFilter === 'all' || this.typeFilter === 'resale') {
        row.push(group.resaleRate !== undefined ? `₹${group.resaleRate.toFixed(2)}` : '-');
      }
      
      return row;
    });

    // Add table
    autoTable(doc, {
      head: [headers],
      body: tableData,
      startY: yPos,
      theme: 'striped',
      headStyles: {
        fillColor: [37, 99, 235],
        textColor: 255,
        fontStyle: 'bold',
        fontSize: 10
      },
      styles: {
        fontSize: 9,
        cellPadding: 3
      },
      columnStyles: {
        0: { cellWidth: 80 }, // Product Name
        1: { cellWidth: 30 }, // Size
      },
      didDrawPage: (data: any) => {
        // Add watermark on each page
        addWatermark(doc);
      }
    });

    // Add watermark to the document
    addWatermark(doc);

    // Generate filename
    const filename = `Rate_List_${dateFilter}_${typeFilter.replace(' ', '_')}.pdf`;
    
    // Download
    doc.save(filename);
  }

  openBulkUploadModal(): void {
    if (!this.canUploadRateListFiles) {
      this.permissionService.notifyRoleDenied('upload rate list files', 'rateListUpload');
      return;
    }
    this.showBulkUploadModal = true;
    this.selectedFile = null;
    this.uploadStatus = 'idle';
    this.uploadMessage = '';
    this.uploadErrors = [];
  }

  closeBulkUploadModal(): void {
    this.showBulkUploadModal = false;
    this.selectedFile = null;
    this.uploadStatus = 'idle';
    this.uploadMessage = '';
    this.uploadErrors = [];
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      const file = input.files[0];
      if (!file.name.toLowerCase().endsWith('.xlsx')) {
        this.uploadStatus = 'error';
        this.uploadMessage = 'Invalid file type. Please upload .xlsx files only.';
        return;
      }
      this.selectedFile = file;
      this.uploadStatus = 'idle';
      this.uploadMessage = '';
      this.uploadErrors = [];
    }
  }

  downloadTemplate(): void {
    if (!this.canUploadRateListFiles) {
      this.permissionService.notifyRoleDenied('download the rate list template', 'rateListUpload');
      return;
    }
    this.api.downloadRateListTemplate().subscribe({
      next: (blob) => {
        const url = window.URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = 'rate_list_template.xlsx';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.URL.revokeObjectURL(url);
        this.notificationService.showSuccess('Template downloaded successfully!', 3000);
      },
      error: (err) => {
        // Error downloading template
        this.notificationService.showError('Failed to download template. Please try again.', 5000);
      }
    });
  }

  uploadFile(): void {
    if (!this.canUploadRateListFiles) {
      this.permissionService.notifyRoleDenied('upload rate list files', 'rateListUpload');
      return;
    }
    if (!this.selectedFile) {
      this.uploadStatus = 'error';
      this.uploadMessage = 'Please select a file first.';
      return;
    }

    this.uploadStatus = 'uploading';
    this.uploadMessage = 'Validating and uploading file...';
    this.uploadErrors = [];

    this.api.bulkUploadRateList(this.selectedFile).subscribe({
      next: (response) => {
        if (response.success) {
          this.uploadStatus = 'success';
          this.uploadMessage = response.message || `Successfully uploaded ${response.count || 0} entries`;
          this.notificationService.showSuccess(this.uploadMessage, 5000);
          
          // Reload rate list entries
          setTimeout(() => {
            this.loadRateListEntries();
            this.closeBulkUploadModal();
          }, 2000);
        } else {
          this.uploadStatus = 'error';
          this.uploadMessage = response.message || 'Upload failed';
          this.uploadErrors = response.errors || [];
          if (response.validCount !== undefined) {
            this.uploadMessage += ` (${response.validCount} valid, ${response.errorCount || 0} errors)`;
          }
        }
      },
      error: (err) => {
        // Error uploading file
        this.uploadStatus = 'error';
        if (err.error?.errors) {
          this.uploadErrors = err.error.errors;
          this.uploadMessage = err.error.message || 'Validation failed. Please check the errors below.';
        } else {
          this.uploadMessage = err.error?.message || 'Failed to upload file. Please try again.';
        }
      }
    });
  }

  startEditSrNo(productName: string, currentSrNo?: number): void {
    // Prevent editing if already loading
    if (this.status === 'loading') {
      return;
    }
    
    // Cancel any existing edit first
    if (this.editingSrNoProduct !== null && this.editingSrNoProduct !== productName) {
      this.cancelEditSrNo();
    }
    
    // Set edit state - don't clear cache as grouped entries don't change
    this.editingSrNoProduct = productName;
    this.editingSrNoValue = currentSrNo ?? null;
    this.showSrNoInput = true;
    
    // Focus the input after Angular completes change detection
    // Use requestAnimationFrame for better timing with Angular's change detection
    requestAnimationFrame(() => {
      setTimeout(() => {
        // Find the input within the table - use a more reliable approach
        const inputs = document.querySelectorAll('.srno-input');
        if (inputs.length > 0) {
          // Focus the first visible input (should be the one we just created)
          const input = Array.from(inputs).find(inp => {
            const elem = inp as HTMLElement;
            return elem.offsetParent !== null; // Check if visible
          }) as HTMLInputElement;
          
          if (input) {
            input.focus();
            input.select();
          }
        }
      }, 50);
    });
  }

  cancelEditSrNo(): void {
    this.editingSrNoProduct = null;
    this.editingSrNoValue = null;
    this.showSrNoInput = false;
  }

  saveSrNo(): void {
    if (!this.editingSrNoProduct || !this.editingSrNoValue || this.editingSrNoValue <= 0) {
      this.notificationService.showError('Please enter a valid serial number (greater than 0)', 3000);
      return;
    }

    // Prevent multiple simultaneous saves
    if (this.status === 'loading') {
      return;
    }

    // Store values before clearing edit state
    const productName = this.editingSrNoProduct;
    const srNoValue = this.editingSrNoValue;

    // Check if srNo is already assigned to a different product
    const existingProduct = this.getProductWithSrNo(srNoValue);
    if (existingProduct && existingProduct !== productName) {
      this.notificationService.showError(
        `Serial number ${srNoValue} is already assigned to product '${existingProduct}'. Please use a different serial number.`,
        5000
      );
      return;
    }

    const successMessage = `Serial number updated successfully!`;

    // Clear edit state immediately to update UI and prevent rendering conflicts
    this.cancelEditSrNo();

    this.status = 'loading';
    this.api.updateProductSrNo(productName, srNoValue).subscribe({
      next: (response) => {
        // Use setTimeout to ensure DOM updates before reloading data
        // This prevents conflicts with Angular's change detection
        setTimeout(() => {
          this.loadRateListEntries();
          // Show success notification after reload completes
          setTimeout(() => {
            this.notificationService.showSuccess(response.message || successMessage, 5000);
          }, 300);
        }, 50);
      },
      error: (err: HttpErrorResponse) => {
        // Error updating srNo
        this.status = 'failed';
        // Check if it's a conflict error (409) for duplicate srNo
        if (err.status === 409) {
          const errorMessage = err.error?.message || 'This serial number is already assigned to another product';
          this.notificationService.showError(errorMessage, 5000);
        } else {
          const errorMessage = err.error?.message || 'Failed to update serial number';
          this.notificationService.showError(errorMessage, 5000);
        }
        // Restore edit state on error so user can retry
        this.startEditSrNo(productName, srNoValue);
      }
    });
  }

  // Helper method to check if a srNo is already assigned to a product
  private getProductWithSrNo(srNo: number): string | null {
    // Check all entries to find if this srNo is already assigned
    for (const entry of this.rateListEntries) {
      if (entry.srNo === srNo && entry.productName !== this.editingSrNoProduct) {
        return entry.productName;
      }
    }
    return null;
  }

  migrateSrNo(): void {
    if (!confirm('This will assign serial numbers to all products that don\'t have one. Continue?')) {
      return;
    }

    this.status = 'loading';
    this.api.migrateSrNo().subscribe({
      next: (response) => {
        this.loadRateListEntries();
        this.status = 'idle';
        this.notificationService.showSuccess(response.message || 'Serial numbers migrated successfully!', 5000);
      },
      error: (err: HttpErrorResponse) => {
        // Error migrating srNo
        this.status = 'failed';
        const errorMessage = err.error?.message || 'Failed to migrate serial numbers';
        this.notificationService.showError(errorMessage, 5000);
      }
    });
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }
}

