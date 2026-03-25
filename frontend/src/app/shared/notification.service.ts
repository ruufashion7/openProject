import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

export interface Notification {
  id: string;
  message: string;
  type: 'error' | 'success' | 'info' | 'warning';
  duration?: number; // Duration in milliseconds, undefined means manual dismiss
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private notificationsSubject = new BehaviorSubject<Notification[]>([]);
  public notifications$: Observable<Notification[]> = this.notificationsSubject.asObservable();

  private notificationIdCounter = 0;

  show(message: string, type: 'error' | 'success' | 'info' | 'warning' = 'error', duration?: number): void {
    const notification: Notification = {
      id: `notification-${++this.notificationIdCounter}`,
      message,
      type,
      duration
    };

    const currentNotifications = this.notificationsSubject.value;
    this.notificationsSubject.next([...currentNotifications, notification]);

    // Auto-dismiss if duration is specified
    if (duration !== undefined) {
      setTimeout(() => {
        this.dismiss(notification.id);
      }, duration);
    }
  }

  showPermissionError(): void {
    this.show('No access, please contact admin', 'error', 30000); // 30 seconds
  }

  /**
   * When the user tries an action blocked by a specific permission (Outstanding / Access Control).
   */
  showRoleRequired(actionDescription: string, permissionLabel: string): void {
    this.show(
      `You don't have permission to ${actionDescription}. Ask an admin to enable "${permissionLabel}" in Access Control.`,
      'warning',
      10000
    );
  }

  showSuccess(message: string, duration: number = 3000): void {
    this.show(message, 'success', duration);
  }

  showError(message: string, duration: number = 30000): void {
    this.show(message, 'error', duration);
  }

  showInfo(message: string, duration: number = 3000): void {
    this.show(message, 'info', duration);
  }

  showWarning(message: string, duration: number = 5000): void {
    this.show(message, 'warning', duration);
  }

  dismiss(id: string): void {
    const currentNotifications = this.notificationsSubject.value;
    this.notificationsSubject.next(currentNotifications.filter(n => n.id !== id));
  }

  clearAll(): void {
    this.notificationsSubject.next([]);
  }
}

