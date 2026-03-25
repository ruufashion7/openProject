import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { finalize } from 'rxjs/operators';
import { AuthService } from '../auth/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css'
})
export class LoginComponent {
  username = '';
  password = '';
  error = '';
  /** True while POST /api/login is in flight — disables button to prevent double submit */
  loading = false;

  constructor(private auth: AuthService, private router: Router) {}

  submit(): void {
    if (this.loading) {
      return;
    }
    this.error = '';
    
    // SECURITY: Sanitize input
    const sanitizedUsername = this.username.trim();
    const sanitizedPassword = this.password;
    
    // SECURITY: Basic input validation
    if (!sanitizedUsername || sanitizedUsername.length < 3) {
      this.error = 'Username must be at least 3 characters.';
      return;
    }
    
    if (!sanitizedPassword || sanitizedPassword.length < 1) {
      this.error = 'Password is required.';
      return;
    }
    
    // SECURITY: Prevent extremely long inputs (potential DoS)
    if (sanitizedUsername.length > 50 || sanitizedPassword.length > 200) {
      this.error = 'Input too long.';
      return;
    }
    
    this.loading = true;
    this.auth
      .login(sanitizedUsername, sanitizedPassword)
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (ok) => {
          if (ok) {
            this.router.navigateByUrl('/welcome');
            return;
          }
          this.error = 'Invalid credentials.';
        },
        error: (err: HttpErrorResponse) => {
          // SECURITY: Don't expose internal stack traces; status codes are safe to branch on
          if (err.status === 429) {
            this.error = 'Too many login attempts. Please try again later.';
          } else if (err.status === 401) {
            this.error = 'Invalid credentials.';
          } else if (err.status === 0) {
            this.error = 'Network error. Check your connection and try again.';
          } else if (err.status === 502 || err.status === 503 || err.status === 504) {
            this.error =
              'Sign-in service is temporarily unavailable (gateway timeout). Please try again in a moment.';
          } else if (err.status >= 500) {
            this.error = 'Server error. Please try again later.';
          } else {
            this.error = 'Login failed. Please try again.';
          }
        }
      });
  }
}

