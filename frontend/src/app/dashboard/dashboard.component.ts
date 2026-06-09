import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../services/api.service';
import { PageStateComponent } from '../shared/page-state/page-state.component';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, PageStateComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css'
})
export class DashboardComponent {
  health$ = this.api.getHealth();
  summary$ = this.api.getDashboard();

  constructor(private api: ApiService) {}
}

