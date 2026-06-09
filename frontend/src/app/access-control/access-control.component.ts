import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { AuthService, UserPermissions } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { PERMISSIONS, getDefaultPermissions, getAllTruePermissions } from '../auth/permissions.config';
import {
  DEFAULT_PASSWORD_POLICY,
  evaluatePasswordRules,
  passwordMeetsPolicy,
  PasswordRuleCheck
} from '../auth/password-policy';
import { NotificationService } from '../shared/notification.service';
import { PageStateComponent } from '../shared/page-state/page-state.component';
import { messageFromHttpError } from '../shared/api-error.util';
import { HttpErrorResponse } from '@angular/common/http';

interface User {
  id: string;
  username: string;
  displayName: string;
  isAdmin: boolean;
  permissions: UserPermissions;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
  active: boolean;
}

@Component({
  selector: 'app-access-control',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, PageStateComponent],
  templateUrl: './access-control.component.html',
  styleUrl: './access-control.component.css'
})
export class AccessControlComponent implements OnInit {
  users: User[] = [];
  selectedUser: User | null = null;
  isEditing = false;
  isCreating = false;
  currentAdmin: User | null = null;
  status: 'idle' | 'loading' | 'failed' = 'idle';
  saving = false;
  
  newUser = {
    username: '',
    password: '',
    displayName: '',
    isAdmin: false,
    permissions: getDefaultPermissions()
  };

  editedUser: Partial<User> & { permissions?: UserPermissions } = {};

  /** Optional: set a new password when editing (leave blank to keep current). */
  editPasswordNew = '';
  editPasswordConfirm = '';
  /** Required when editing your own account and setting a new password (verified server-side). */
  editPasswordCurrent = '';

  /** Matches server {@code security.password.*} defaults; live hints stay in sync with backend validation. */
  readonly passwordPolicy = DEFAULT_PASSWORD_POLICY;

  constructor(
    private http: HttpClient,
    private authService: AuthService,
    private permissionService: PermissionService,
    private router: Router,
    private notifications: NotificationService
  ) {}

  ngOnInit(): void {
    if (!this.permissionService.isAdmin()) {
      // Redirect if not admin
      return;
    }
    this.loadUsers();
  }

  loadUsers(): void {
    this.status = 'loading';
    const headers = this.authService.getAuthHeaders();
    this.http.get<User[]>('/api/users', { headers }).subscribe({
      next: (users) => {
        this.users = users;
        this.currentAdmin = users.find(u => u.isAdmin && u.active) || null;
        this.status = 'idle';
      },
      error: (error: HttpErrorResponse) => {
        this.status = 'failed';
        this.notifications.showError(messageFromHttpError(error, 'Failed to load users'));
      }
    });
  }

  hasAdmin(): boolean {
    return this.currentAdmin !== null;
  }

  isCurrentUserAdmin(): boolean {
    const currentUserId = this.authService.getUserId();
    return this.currentAdmin?.id === currentUserId;
  }

  canMakeAdmin(): boolean {
    // Can make admin if there's no admin, or if editing the current admin
    return !this.hasAdmin() || (this.selectedUser?.id === this.currentAdmin?.id);
  }

  startCreate(): void {
    this.isCreating = true;
    this.isEditing = false;
    this.selectedUser = null;
    this.newUser = {
      username: '',
      password: '',
      displayName: '',
      isAdmin: false,
      permissions: getDefaultPermissions()
    };
  }

  cancelCreate(): void {
    this.isCreating = false;
    this.newUser = {
      username: '',
      password: '',
      displayName: '',
      isAdmin: false,
      permissions: getDefaultPermissions()
    };
  }

  createUser(): void {
    if (!this.newUser.username || !this.newUser.password || !this.newUser.displayName) {
      this.notifications.showError('Please fill in all required fields');
      return;
    }

    if (!passwordMeetsPolicy(this.newUser.password, this.passwordPolicy)) {
      return;
    }

    if (this.newUser.isAdmin && this.hasAdmin()) {
      if (!confirm('Making this user admin will demote the current admin. Continue?')) {
        return;
      }
    }

    this.saving = true;
    const headers = this.authService.getAuthHeaders();
    this.http.post<User>('/api/users', this.newUser, { headers }).subscribe({
      next: () => {
        this.saving = false;
        this.loadUsers();
        this.cancelCreate();
        const message = this.newUser.isAdmin && this.hasAdmin()
          ? 'User created successfully. Previous admin has been demoted.'
          : 'User created successfully';
        this.notifications.showSuccess(message);
      },
      error: (error: HttpErrorResponse) => {
        this.saving = false;
        this.notifications.showError(messageFromHttpError(error, 'Failed to create user. Username may already exist.'));
      }
    });
  }

  startEdit(user: User): void {
    this.selectedUser = user;
    this.isEditing = true;
    this.isCreating = false;
    this.editPasswordNew = '';
    this.editPasswordConfirm = '';
    this.editPasswordCurrent = '';
    this.editedUser = {
      displayName: user.displayName,
      isAdmin: user.isAdmin,
      active: user.isAdmin ? true : user.active, // Super admin is always active
      permissions: { ...user.permissions }
    };
  }

  cancelEdit(): void {
    this.isEditing = false;
    this.selectedUser = null;
    this.editedUser = {};
    this.editPasswordNew = '';
    this.editPasswordConfirm = '';
    this.editPasswordCurrent = '';
  }

  /** True when the row being edited is the logged-in user (password change needs current password). */
  isEditingOwnAccount(): boolean {
    return !!this.selectedUser && this.selectedUser.id === this.authService.getUserId();
  }

  getCreatePasswordRules(): PasswordRuleCheck[] {
    return evaluatePasswordRules(this.newUser.password ?? '', this.passwordPolicy);
  }

  getEditPasswordRules(): PasswordRuleCheck[] {
    return evaluatePasswordRules(this.editPasswordNew, this.passwordPolicy);
  }

  editPasswordHasNew(): boolean {
    return this.editPasswordNew.trim().length > 0;
  }

  /** Live: new password and confirmation differ (only when both have content). */
  editConfirmMismatch(): boolean {
    const a = this.editPasswordNew.trim();
    const b = this.editPasswordConfirm.trim();
    return a.length > 0 && b.length > 0 && a !== b;
  }

  /** Live: new password set but current password empty (required for self and when admin changes another user). */
  currentPasswordMissingForEdit(): boolean {
    return (
      this.editPasswordNew.trim().length > 0 &&
      this.editPasswordCurrent.trim().length === 0
    );
  }

  canSubmitCreate(): boolean {
    return (
      !!this.newUser.username?.trim() &&
      !!this.newUser.displayName?.trim() &&
      !!this.newUser.password &&
      passwordMeetsPolicy(this.newUser.password, this.passwordPolicy)
    );
  }

  canSubmitEdit(): boolean {
    if (!this.selectedUser) {
      return false;
    }
    if (!this.editedUser.displayName?.trim()) {
      return false;
    }
    const newPw = this.editPasswordNew.trim();
    const confirmPw = this.editPasswordConfirm.trim();
    if (newPw || confirmPw) {
      if (newPw !== confirmPw) {
        return false;
      }
      if (newPw && !passwordMeetsPolicy(newPw, this.passwordPolicy)) {
        return false;
      }
      if (newPw && !this.editPasswordCurrent.trim()) {
        return false;
      }
    }
    return true;
  }

  updateUser(): void {
    if (!this.selectedUser) {
      return;
    }

    // Check if promoting to admin when there's already an admin
    const wasAdmin = this.selectedUser.isAdmin;
    const willBeAdmin = this.editedUser.isAdmin === true;
    
    if (willBeAdmin && !wasAdmin && this.hasAdmin()) {
      if (!confirm('Making this user admin will demote the current admin. Continue?')) {
        return;
      }
    }

    // Check if demoting current admin
    if (wasAdmin && this.editedUser.isAdmin === false && this.selectedUser.id === this.currentAdmin?.id) {
      if (!confirm('You are about to demote yourself from admin. You will lose admin access. Continue?')) {
        return;
      }
    }

    // Ensure super admin is always active
    if (this.editedUser.isAdmin || wasAdmin) {
      this.editedUser.active = true;
    }

    const newPw = this.editPasswordNew.trim();
    const confirmPw = this.editPasswordConfirm.trim();
    const editedSelf = this.selectedUser.id === this.authService.getUserId();
    if (newPw || confirmPw) {
      if (newPw !== confirmPw) {
        return;
      }
    }
    if (newPw && !passwordMeetsPolicy(newPw, this.passwordPolicy)) {
      return;
    }
    if (newPw) {
      const cur = this.editPasswordCurrent.trim();
      if (!cur) {
        return;
      }
    }

    const headers = this.authService.getAuthHeaders();
    const payload: {
      displayName: string;
      isAdmin: boolean;
      permissions: UserPermissions;
      active: boolean;
      password?: string;
      currentPassword?: string;
    } = {
      displayName: this.editedUser.displayName!,
      isAdmin: this.editedUser.isAdmin!,
      permissions: this.editedUser.permissions!,
      active: this.editedUser.active!
    };
    if (newPw) {
      payload.password = newPw;
      payload.currentPassword = this.editPasswordCurrent.trim();
    }

    const passwordChanged = !!newPw;

    this.saving = true;
    this.http.put<User>(`/api/users/${this.selectedUser.id}`, payload, { headers }).subscribe({
      next: () => {
        this.saving = false;
        this.loadUsers();
        this.cancelEdit();
        let message = 'User updated successfully';
        if (willBeAdmin && !wasAdmin && this.hasAdmin()) {
          message = 'User updated successfully. Previous admin has been demoted.';
        } else if (wasAdmin && this.editedUser.isAdmin === false) {
          message = 'User updated successfully. Admin access removed.';
        }
        if (passwordChanged) {
          message += ' Password was updated; all sessions for this user were signed out.';
        }
        this.notifications.showSuccess(message);
        if (passwordChanged && editedSelf) {
          this.authService.logout();
          this.router.navigateByUrl('/login');
        }
      },
      error: (error: HttpErrorResponse) => {
        this.saving = false;
        this.notifications.showError(messageFromHttpError(error, 'Failed to update user'));
      }
    });
  }

  deleteUser(user: User): void {
    if (user.isAdmin && this.hasAdmin() && this.currentAdmin?.id === user.id) {
      this.notifications.showError('Cannot deactivate the last admin user. Please assign admin to another user first.');
      return;
    }

    if (!confirm(`Are you sure you want to deactivate user "${user.displayName}"?`)) {
      return;
    }

    const headers = this.authService.getAuthHeaders();
    this.http.delete(`/api/users/${user.id}`, { headers }).subscribe({
      next: () => {
        this.loadUsers();
        this.notifications.showSuccess('User deactivated successfully');
      },
      error: (error: HttpErrorResponse) => {
        this.notifications.showError(messageFromHttpError(error, 'Failed to deactivate user'));
      }
    });
  }

  activateUser(user: User): void {
    if (!confirm(`Are you sure you want to activate user "${user.displayName}"?`)) {
      return;
    }

    const headers = this.authService.getAuthHeaders();
    const updateData = {
      displayName: user.displayName,
      isAdmin: user.isAdmin,
      permissions: user.permissions,
      active: true
    };
    
    this.http.put<User>(`/api/users/${user.id}`, updateData, { headers }).subscribe({
      next: () => {
        this.loadUsers();
        this.notifications.showSuccess('User activated successfully');
      },
      error: (error: HttpErrorResponse) => {
        this.notifications.showError(messageFromHttpError(error, 'Failed to activate user'));
      }
    });
  }

  togglePermission(permission: keyof UserPermissions, isNewUser: boolean = false): void {
    if (isNewUser) {
      this.newUser.permissions[permission] = !this.newUser.permissions[permission];
    } else if (this.editedUser.permissions) {
      this.editedUser.permissions[permission] = !this.editedUser.permissions[permission];
    }
  }

  toggleAllPermissions(isNewUser: boolean = false): void {
    const allTrue = isNewUser
      ? Object.values(this.newUser.permissions).every(v => v === true)
      : this.editedUser.permissions
        ? Object.values(this.editedUser.permissions).every(v => v === true)
        : false;

    const newValue = !allTrue;
    const permissions = newValue ? getAllTruePermissions() : getDefaultPermissions();

    if (isNewUser) {
      this.newUser.permissions = { ...permissions };
    } else {
      this.editedUser.permissions = { ...permissions };
    }
  }

  // Get permissions list for template iteration
  getPermissionsList() {
    return PERMISSIONS;
  }
}

