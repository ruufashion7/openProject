import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
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

  constructor(private auth: AuthService, private router: Router) {}

  submit(): void {
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
    
    this.auth.login(sanitizedUsername, sanitizedPassword).subscribe({
      next: (ok) => {
        if (ok) {
          this.router.navigateByUrl('/welcome');
          return;
        }
        this.error = 'Invalid credentials.';
      },
      error: (err) => {
        // SECURITY: Don't expose internal error details
        if (err.status === 429) {
          this.error = 'Too many login attempts. Please try again later.';
        } else if (err.status === 401) {
          this.error = 'Invalid credentials.';
        } else {
          this.error = 'Login failed. Please try again.';
        }
      }
    });
  }
}

