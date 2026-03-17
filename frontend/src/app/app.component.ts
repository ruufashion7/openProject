import { Component, OnInit } from '@angular/core';
import { RouterOutlet, Router, NavigationEnd } from '@angular/router';
import { CommonModule } from '@angular/common';
import { SidebarComponent } from './shared/sidebar/sidebar.component';
import { ScrollButtonComponent } from './shared/scroll-button/scroll-button.component';
import { TopHeaderComponent } from './shared/top-header/top-header.component';
import { NotificationComponent } from './shared/notification/notification.component';
import { filter } from 'rxjs/operators';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, CommonModule, SidebarComponent, ScrollButtonComponent, TopHeaderComponent, NotificationComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent implements OnInit {
  showSidebar = false;

  constructor(private router: Router) {}

  ngOnInit(): void {
    // Show sidebar only on authenticated routes (not on login page)
    this.router.events
      .pipe(filter(event => event instanceof NavigationEnd))
      .subscribe((event: any) => {
        this.showSidebar = event.urlAfterRedirects !== '/login' && event.urlAfterRedirects !== '/';
      });
    
    // Check initial route
    this.showSidebar = this.router.url !== '/login' && this.router.url !== '/';
  }
}
