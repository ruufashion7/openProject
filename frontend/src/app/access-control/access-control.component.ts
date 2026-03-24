import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { AuthService, UserPermissions } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { PERMISSIONS, getDefaultPermissions, getAllTruePermissions } from '../auth/permissions.config';

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
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './access-control.component.html',
  styleUrl: './access-control.component.css'
})
export class AccessControlComponent implements OnInit {
  users: User[] = [];
  selectedUser: User | null = null;
  isEditing = false;
  isCreating = false;
  currentAdmin: User | null = null;
  
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

  constructor(
    private http: HttpClient,
    private authService: AuthService,
    private permissionService: PermissionService,
    private router: Router
  ) {}

  ngOnInit(): void {
    if (!this.permissionService.isAdmin()) {
      // Redirect if not admin
      return;
    }
    this.loadUsers();
  }

  loadUsers(): void {
    const headers = this.authService.getAuthHeaders();
    this.http.get<User[]>('/api/users', { headers }).subscribe({
      next: (users) => {
        this.users = users;
        this.currentAdmin = users.find(u => u.isAdmin && u.active) || null;
      },
      error: (error) => {
        // Error loading users
        alert('Failed to load users');
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
      alert('Please fill in all required fields');
      return;
    }

    if (this.newUser.isAdmin && this.hasAdmin()) {
      if (!confirm('Making this user admin will demote the current admin. Continue?')) {
        return;
      }
    }

    const headers = this.authService.getAuthHeaders();
    this.http.post<User>('/api/users', this.newUser, { headers }).subscribe({
      next: () => {
        this.loadUsers();
        this.cancelCreate();
        const message = this.newUser.isAdmin && this.hasAdmin()
          ? 'User created successfully. Previous admin has been demoted.'
          : 'User created successfully';
        alert(message);
      },
      error: (error) => {
        // Error creating user
        const errorMsg = error.error?.message || 'Failed to create user. Username may already exist.';
        alert(errorMsg);
      }
    });
  }

  startEdit(user: User): void {
    this.selectedUser = user;
    this.isEditing = true;
    this.isCreating = false;
    this.editPasswordNew = '';
    this.editPasswordConfirm = '';
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
    if (newPw || confirmPw) {
      if (newPw !== confirmPw) {
        alert('New password and confirmation do not match.');
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
    } = {
      displayName: this.editedUser.displayName!,
      isAdmin: this.editedUser.isAdmin!,
      permissions: this.editedUser.permissions!,
      active: this.editedUser.active!
    };
    if (newPw) {
      payload.password = newPw;
    }

    const passwordChanged = !!newPw;
    const editedSelf = this.selectedUser.id === this.authService.getUserId();

    this.http.put<User>(`/api/users/${this.selectedUser.id}`, payload, { headers }).subscribe({
      next: () => {
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
        alert(message);
        if (passwordChanged && editedSelf) {
          this.authService.logout();
          this.router.navigateByUrl('/login');
        }
      },
      error: (error) => {
        // Error updating user
        const errorMsg = error.error?.message || 'Failed to update user';
        alert(errorMsg);
      }
    });
  }

  deleteUser(user: User): void {
    if (user.isAdmin && this.hasAdmin() && this.currentAdmin?.id === user.id) {
      alert('Cannot deactivate the last admin user. Please assign admin to another user first.');
      return;
    }

    if (!confirm(`Are you sure you want to deactivate user "${user.displayName}"?`)) {
      return;
    }

    const headers = this.authService.getAuthHeaders();
    this.http.delete(`/api/users/${user.id}`, { headers }).subscribe({
      next: () => {
        this.loadUsers();
        alert('User deactivated successfully');
      },
      error: (error) => {
        // Error deleting user
        const errorMsg = error.error?.message || 'Failed to deactivate user';
        alert(errorMsg);
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
        alert('User activated successfully');
      },
      error: (error) => {
        // Error activating user
        alert('Failed to activate user');
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

