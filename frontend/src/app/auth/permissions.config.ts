/**
 * Centralized permissions configuration
 * Add new permissions here and they will be automatically available throughout the app
 */
export interface PermissionDefinition {
  key: keyof import('./auth.service').UserPermissions;
  label: string;
  description?: string;
}

export const PERMISSIONS: PermissionDefinition[] = [
  { key: 'fileUpload', label: 'File Upload', description: 'Access to upload files' },
  { key: 'hardDelete', label: 'Hard Delete', description: 'Access to hard delete uploads' },
  { key: 'invoicePage', label: 'Invoice Page', description: 'Access to invoice details page' },
  { key: 'detailsPage', label: 'Details Page', description: 'Access to details/outstanding page' },
  { key: 'wholeProjectDownload', label: 'Whole Project Download', description: 'Access to download whole project' },
  { key: 'outstandingPage', label: 'Outstanding Page', description: 'Access to outstanding/payment dates page' },
  { key: 'paymentDateEdit', label: 'Payment Date Edit', description: 'Permission to edit payment dates' },
  { key: 'whatsappDateChange', label: 'WhatsApp Date Change', description: 'Permission to change WhatsApp dates' },
  { key: 'followUpChange', label: 'Follow Up Change', description: 'Permission to change follow-up flags' },
  { key: 'customerCategoryEdit', label: 'Customer Category Edit', description: 'Edit customer category (A/B/C, etc.) on Details or Outstanding' },
  { key: 'customerNotesEdit', label: 'Customer Notes Edit', description: 'Add, edit, or delete customer notes (viewing notes only needs Details or Outstanding page access)' },
  { key: 'customerLocationEdit', label: 'Customer Location Edit', description: 'Edit address, map location, or place on Details or Outstanding' },
  { key: 'rateListPage', label: 'Rate List Page', description: 'Access to rate list page' },
  { key: 'rateListUpload', label: 'Rate List Upload', description: 'Download Excel template and bulk upload on Rate List' },
  { key: 'salesVisualization', label: 'Sales Visualization', description: 'Access to sales visualization and analytics page' },
  { key: 'customerLocations', label: 'Customer Locations', description: 'Access to customer locations map view page' }
];

/**
 * Route to permission mapping for navigation items
 */
export const ROUTE_PERMISSIONS: Record<string, keyof import('./auth.service').UserPermissions | 'admin'> = {
  '/upload': 'fileUpload',
  '/uploads': 'fileUpload',
  '/uploads-audit': 'fileUpload',
  '/rate-list': 'rateListPage',
  '/sales-details': 'invoicePage',
  '/sales-visualization': 'salesVisualization',
  '/outstanding': 'detailsPage',
  '/payment-dates': 'outstandingPage',
  '/customer-locations': 'customerLocations',
  '/access-control': 'admin'
};

/**
 * Get all permission keys
 */
export function getAllPermissionKeys(): Array<keyof import('./auth.service').UserPermissions> {
  return PERMISSIONS.map(p => p.key);
}

/** Label for permission-denied messages (Access Control uses the same names). */
export function getPermissionLabel(key: keyof import('./auth.service').UserPermissions): string {
  return PERMISSIONS.find(p => p.key === key)?.label ?? String(key);
}

/**
 * Get default permissions (all false)
 */
export function getDefaultPermissions(): import('./auth.service').UserPermissions {
  const permissions = {} as import('./auth.service').UserPermissions;
  PERMISSIONS.forEach(p => {
    permissions[p.key] = false;
  });
  return permissions;
}

/** Merge server/partial permission objects with defaults so missing keys are denied (not treated as allowed). */
export function normalizePermissions(
  partial: Partial<import('./auth.service').UserPermissions> | null | undefined
): import('./auth.service').UserPermissions {
  return { ...getDefaultPermissions(), ...(partial ?? {}) } as import('./auth.service').UserPermissions;
}

/**
 * Get all permissions set to true
 */
export function getAllTruePermissions(): import('./auth.service').UserPermissions {
  const permissions = {} as import('./auth.service').UserPermissions;
  PERMISSIONS.forEach(p => {
    permissions[p.key] = true;
  });
  return permissions;
}

