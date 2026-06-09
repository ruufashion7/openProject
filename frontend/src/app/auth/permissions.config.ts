/**
 * Centralized permissions — grouped by sidebar section for Access Control UI.
 * Add new permissions to a group here; they flow through the app automatically.
 */
export type PermissionKind = 'page' | 'action';

export interface PermissionDefinition {
  key: keyof import('./auth.service').UserPermissions;
  label: string;
  description: string;
  kind: PermissionKind;
  routes?: string[];
  /** Action permissions need at least one of these page keys (shown as hint in UI). */
  requiresAny?: Array<keyof import('./auth.service').UserPermissions>;
}

export interface PermissionGroup {
  id: string;
  label: string;
  icon: string;
  summary: string;
  items: PermissionDefinition[];
}

/** Admin-only pages — not grantable per-user; use the Admin User checkbox. */
export const ADMIN_ONLY_PAGES: Array<{ label: string; route: string; description: string }> = [
  {
    label: 'Operations Overview',
    route: '/dashboard',
    description: 'Live system health, uploads, users, and outreach metrics'
  },
  {
    label: 'Sessions',
    route: '/sessions',
    description: 'View and revoke active login sessions'
  },
  {
    label: 'Access Control',
    route: '/access-control',
    description: 'Manage users and permissions (this page)'
  }
];

export const PERMISSION_GROUPS: PermissionGroup[] = [
  {
    id: 'data-import',
    label: 'Data Import',
    icon: '📤',
    summary: 'Upload sales/receivable files, view history, audit trail, and hard delete.',
    items: [
      {
        key: 'fileUpload',
        label: 'Upload Files',
        description: 'Upload new Excel files and manage in-progress jobs',
        kind: 'page',
        routes: ['/upload']
      },
      {
        key: 'uploadsListPage',
        label: 'Latest Files',
        description: 'View and download currently loaded upload batches',
        kind: 'page',
        routes: ['/uploads']
      },
      {
        key: 'uploadAuditPage',
        label: 'Audit Trail',
        description: 'View upload history and who uploaded what',
        kind: 'page',
        routes: ['/uploads-audit']
      },
      {
        key: 'hardDelete',
        label: 'Hard Delete',
        description: 'Permanently purge upload data from the database',
        kind: 'page',
        routes: ['/uploads-purge']
      }
    ]
  },
  {
    id: 'pricing',
    label: 'Pricing',
    icon: '💵',
    summary: 'Rate list viewing and bulk Excel upload.',
    items: [
      {
        key: 'rateListPage',
        label: 'Rate List',
        description: 'Open the rate list page and view/export rates',
        kind: 'page',
        routes: ['/rate-list']
      },
      {
        key: 'rateListUpload',
        label: 'Rate List Upload',
        description: 'Download Excel template and bulk-upload rates',
        kind: 'action',
        routes: ['/rate-list'],
        requiresAny: ['rateListPage']
      }
    ]
  },
  {
    id: 'customers-sales',
    label: 'Customers & Sales',
    icon: '📋',
    summary: 'Customer pages plus edit actions on payment dates and customer details.',
    items: [
      {
        key: 'outstandingPage',
        label: 'Payment Dates',
        description: 'Payment dates board with filters and exports',
        kind: 'page',
        routes: ['/payment-dates']
      },
      {
        key: 'detailsPage',
        label: 'Customer Details',
        description: 'Outstanding / customer details with search and exports',
        kind: 'page',
        routes: ['/outstanding']
      },
      {
        key: 'invoicePage',
        label: 'Invoice Details',
        description: 'Sales invoice line-item lookup',
        kind: 'page',
        routes: ['/sales-details']
      },
      {
        key: 'salesVisualization',
        label: 'Sales Analytics',
        description: 'Charts and analytics across sales data',
        kind: 'page',
        routes: ['/sales-visualization']
      },
      {
        key: 'wholeProjectDownload',
        label: 'Whole Project Download',
        description: 'Download full project export from Customer Details',
        kind: 'action',
        routes: ['/outstanding'],
        requiresAny: ['detailsPage', 'outstandingPage']
      },
      {
        key: 'paymentDateEdit',
        label: 'Edit Payment Dates',
        description: 'Change payment due dates on Customer Details or Payment Dates',
        kind: 'action',
        requiresAny: ['detailsPage', 'outstandingPage']
      },
      {
        key: 'whatsappDateChange',
        label: 'Change WhatsApp Status',
        description: 'Update WhatsApp sent/delivered flags on customer cards',
        kind: 'action',
        requiresAny: ['detailsPage', 'outstandingPage']
      },
      {
        key: 'followUpChange',
        label: 'Change Follow-up Flags',
        description: 'Mark customers as follow-up needed or not',
        kind: 'action',
        requiresAny: ['detailsPage', 'outstandingPage']
      },
      {
        key: 'customerCategoryEdit',
        label: 'Edit Customer Category',
        description: 'Set category A / B / C / semi-wholesale',
        kind: 'action',
        requiresAny: ['detailsPage', 'outstandingPage']
      },
      {
        key: 'customerNotesEdit',
        label: 'Edit Customer Notes',
        description: 'Add, edit, or delete notes (viewing needs page access only)',
        kind: 'action',
        requiresAny: ['detailsPage', 'outstandingPage']
      },
      {
        key: 'customerLocationEdit',
        label: 'Edit Customer Location',
        description: 'Edit address, map pin, or place on customer cards',
        kind: 'action',
        requiresAny: ['detailsPage', 'outstandingPage']
      }
    ]
  },
  {
    id: 'outreach',
    label: 'Outreach',
    icon: '💬',
    summary: 'WhatsApp batches and customer map.',
    items: [
      {
        key: 'whatsappBroadcast',
        label: 'WhatsApp Outreach',
        description: 'Compose wa.me messages and track broadcast batches',
        kind: 'page',
        routes: ['/whatsapp-outreach']
      },
      {
        key: 'customerLocations',
        label: 'Customer Locations',
        description: 'Map view of customer addresses',
        kind: 'page',
        routes: ['/customer-locations']
      }
    ]
  }
];

/** Flat list — used by normalize/getAll helpers and backward-compatible iteration. */
export const PERMISSIONS: PermissionDefinition[] = PERMISSION_GROUPS.flatMap((g) => g.items);

/**
 * Route to permission mapping for navigation items
 */
export const ROUTE_PERMISSIONS: Record<string, keyof import('./auth.service').UserPermissions | 'admin'> = {
  '/upload': 'fileUpload',
  '/uploads': 'uploadsListPage',
  '/uploads-audit': 'uploadAuditPage',
  '/uploads-purge': 'hardDelete',
  '/rate-list': 'rateListPage',
  '/sales-details': 'invoicePage',
  '/sales-visualization': 'salesVisualization',
  '/outstanding': 'detailsPage',
  '/payment-dates': 'outstandingPage',
  '/whatsapp-outreach': 'whatsappBroadcast',
  '/customer-locations': 'customerLocations',
  '/access-control': 'admin',
  '/sessions': 'admin',
  '/dashboard': 'admin'
};

export function getAllPermissionKeys(): Array<keyof import('./auth.service').UserPermissions> {
  return PERMISSIONS.map((p) => p.key);
}

export function getPermissionLabel(key: keyof import('./auth.service').UserPermissions): string {
  return PERMISSIONS.find((p) => p.key === key)?.label ?? String(key);
}

export function getDefaultPermissions(): import('./auth.service').UserPermissions {
  const permissions = {} as import('./auth.service').UserPermissions;
  PERMISSIONS.forEach((p) => {
    permissions[p.key] = false;
  });
  return permissions;
}

export function normalizePermissions(
  partial: Partial<import('./auth.service').UserPermissions> | null | undefined
): import('./auth.service').UserPermissions {
  return { ...getDefaultPermissions(), ...(partial ?? {}) } as import('./auth.service').UserPermissions;
}

export function getAllTruePermissions(): import('./auth.service').UserPermissions {
  const permissions = {} as import('./auth.service').UserPermissions;
  PERMISSIONS.forEach((p) => {
    permissions[p.key] = true;
  });
  return permissions;
}

export function getGroupPermissionKeys(group: PermissionGroup): Array<keyof import('./auth.service').UserPermissions> {
  return group.items.map((item) => item.key);
}

export function filterPermissionGroups(
  query: string,
  groups: PermissionGroup[] = PERMISSION_GROUPS
): PermissionGroup[] {
  const q = query.trim().toLowerCase();
  if (!q) {
    return groups;
  }
  return groups
    .map((group) => {
      const items = group.items.filter(
        (item) =>
          item.label.toLowerCase().includes(q) ||
          item.description.toLowerCase().includes(q) ||
          item.key.toLowerCase().includes(q) ||
          (item.routes?.some((r) => r.includes(q)) ?? false)
      );
      if (
        items.length === 0 &&
        !group.label.toLowerCase().includes(q) &&
        !group.summary.toLowerCase().includes(q)
      ) {
        return null;
      }
      return { ...group, items: items.length ? items : group.items };
    })
    .filter((g): g is PermissionGroup => g !== null);
}
