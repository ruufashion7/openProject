import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService, SalesVisualizationResponse } from '../services/api.service';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
import { ChartConfiguration, ChartData, ChartOptions, ChartType, TooltipItem } from 'chart.js';
import { NgChartsModule } from 'ng2-charts';

@Component({
  selector: 'app-sales-visualization',
  standalone: true,
  imports: [CommonModule, FormsModule, NgChartsModule],
  templateUrl: './sales-visualization.component.html',
  styleUrl: './sales-visualization.component.css'
})
export class SalesVisualizationComponent implements OnInit, OnDestroy {
  status: 'idle' | 'loading' | 'failed' = 'idle';
  message = '';
  ready = false;
  data: SalesVisualizationResponse | null = null;

  // Filters
  yearFilter = '';
  monthFilter = '';
  quarterFilter = '';
  datePreset: 'none' | 'thisYear' | 'lastYear' | 'thisQuarter' | 'lastQuarter' | 'thisMonth' | 'lastMonth' = 'none';

  // Chart configurations
  monthlyTrendChartData: ChartData<'line'> = {
    labels: [],
    datasets: []
  };
  monthlyTrendChartOptions: ChartConfiguration<'line'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        display: true,
        position: 'top'
      },
      title: {
        display: true,
        text: 'Monthly Sales Trend',
        font: {
          size: 16,
          weight: 'bold'
        }
      },
      tooltip: {
        mode: 'index',
        intersect: false,
        callbacks: {
          label: (context: TooltipItem<'line'>) => {
            const value = context.parsed?.y ?? 0;
            return `Revenue: ₹${this.formatCurrency(value)}`;
          }
        }
      }
    },
    scales: {
      y: {
        beginAtZero: true,
        ticks: {
          callback: (value: string | number) => {
            return '₹' + this.formatCurrency(value as number);
          }
        }
      }
    }
  };

  topCustomersChartData: ChartData<'bar'> = {
    labels: [],
    datasets: []
  };
  topCustomersChartOptions: ChartConfiguration<'bar'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    indexAxis: 'y',
    plugins: {
      legend: {
        display: false
      },
      title: {
        display: true,
        text: 'Top 10 Customers by Revenue',
        font: {
          size: 16,
          weight: 'bold'
        }
      },
      tooltip: {
        callbacks: {
          label: (context: TooltipItem<'bar'>) => {
            const value = context.parsed?.x ?? 0;
            return `Revenue: ₹${this.formatCurrency(value)}`;
          }
        }
      }
    },
    scales: {
      x: {
        beginAtZero: true,
        ticks: {
          callback: (value: string | number) => {
            return '₹' + this.formatCurrency(value as number);
          }
        }
      }
    }
  };

  paymentStatusChartData: ChartData<'pie'> = {
    labels: [],
    datasets: []
  };
  paymentStatusChartOptions: ChartConfiguration<'pie'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        display: true,
        position: 'right'
      },
      title: {
        display: true,
        text: 'Payment Status Distribution',
        font: {
          size: 16,
          weight: 'bold'
        }
      },
      tooltip: {
        callbacks: {
          label: (context: TooltipItem<'pie'>) => {
            const label = context.label || '';
            const value = typeof context.parsed === 'number' ? context.parsed : 0;
            const datasetData = context.dataset?.data as number[] | undefined;
            const total = datasetData ? datasetData.reduce((a: number, b: number) => a + b, 0) : 0;
            const percentage = total > 0 ? ((value / total) * 100).toFixed(1) : '0';
            return `${label}: ${value} (${percentage}%)`;
          }
        }
      }
    }
  };

  ageingBucketChartData: ChartData<'bar'> = {
    labels: [],
    datasets: []
  };
  ageingBucketChartOptions: ChartConfiguration<'bar'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        display: false
      },
      title: {
        display: true,
        text: 'Outstanding Amount by Ageing Buckets',
        font: {
          size: 16,
          weight: 'bold'
        }
      },
      tooltip: {
        callbacks: {
          label: (context: TooltipItem<'bar'>) => {
            const value = context.parsed?.y ?? 0;
            return `Amount: ₹${this.formatCurrency(value)}`;
          }
        }
      }
    },
    scales: {
      y: {
        beginAtZero: true,
        ticks: {
          callback: (value: string | number) => {
            return '₹' + this.formatCurrency(value as number);
          }
        }
      }
    }
  };

  constructor(
    private api: ApiService,
    private auth: AuthService,
    private router: Router,
    private permissionService: PermissionService,
    private notificationService: NotificationService
  ) {}

  ngOnInit(): void {
    if (!this.permissionService.canAccessSalesVisualization()) {
      this.notificationService.showPermissionError();
      this.router.navigateByUrl('/welcome');
      return;
    }

    this.status = 'loading';
    this.api.getUploadStatus().subscribe({
      next: (status) => {
        this.ready = status.ready ?? (status.hasDetailed && status.hasReceivable);
        this.status = 'idle';
        if (this.ready) {
          this.loadVisualization();
        } else {
          this.message = 'Latest uploads not available.';
        }
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.status = 'failed';
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        }
        this.status = 'failed';
        this.message = 'Unable to load upload status.';
      }
    });
  }

  loadVisualization(): void {
    this.status = 'loading';
    const params: any = {};
    if (this.yearFilter) params.year = parseInt(this.yearFilter, 10);
    if (this.monthFilter) params.month = parseInt(this.monthFilter, 10);
    if (this.quarterFilter) params.quarter = parseInt(this.quarterFilter, 10);

    this.api.getSalesVisualization(params).subscribe({
      next: (response) => {
        this.data = response;
        this.updateCharts();
        this.status = 'idle';
        this.message = '';
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.status = 'failed';
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        }
        this.status = 'failed';
        this.message = 'Unable to load sales visualization data.';
      }
    });
  }

  updateCharts(): void {
    if (!this.data) return;

    // Monthly trends chart
    this.monthlyTrendChartData = {
      labels: this.data.monthlyTrends.map(t => t.month),
      datasets: [{
        label: 'Revenue',
        data: this.data.monthlyTrends.map(t => t.revenue),
        borderColor: 'rgb(37, 99, 235)',
        backgroundColor: 'rgba(37, 99, 235, 0.1)',
        tension: 0.4,
        fill: true
      }]
    };

    // Top customers chart
    const topCustomers = this.data.topCustomers.slice(0, 10);
    this.topCustomersChartData = {
      labels: topCustomers.map(c => this.truncateText(c.customer, 30)),
      datasets: [{
        label: 'Revenue',
        data: topCustomers.map(c => c.revenue),
        backgroundColor: [
          'rgba(37, 99, 235, 0.8)',
          'rgba(59, 130, 246, 0.8)',
          'rgba(96, 165, 250, 0.8)',
          'rgba(147, 197, 253, 0.8)',
          'rgba(191, 219, 254, 0.8)',
          'rgba(219, 234, 254, 0.8)',
          'rgba(239, 246, 255, 0.8)',
          'rgba(37, 99, 235, 0.6)',
          'rgba(59, 130, 246, 0.6)',
          'rgba(96, 165, 250, 0.6)'
        ]
      }]
    };

    // Payment status pie chart
    this.paymentStatusChartData = {
      labels: this.data.paymentStatusDistribution.map(p => p.status),
      datasets: [{
        data: this.data.paymentStatusDistribution.map(p => p.count),
        backgroundColor: [
          'rgba(6, 118, 71, 0.8)',   // Green for Paid
          'rgba(180, 35, 24, 0.8)',  // Red for Unpaid
          'rgba(243, 229, 22, 0.8)'  // Yellow for Partial
        ],
        borderColor: [
          'rgb(6, 118, 71)',
          'rgb(180, 35, 24)',
          'rgb(243, 229, 22)'
        ],
        borderWidth: 2
      }]
    };

    // Ageing bucket chart
    this.ageingBucketChartData = {
      labels: this.data.ageingBucketAmounts.map(a => a.bucket),
      datasets: [{
        label: 'Outstanding Amount',
        data: this.data.ageingBucketAmounts.map(a => a.amount),
        backgroundColor: [
          'rgba(6, 118, 71, 0.8)',   // Green for 1-45 days
          'rgba(243, 229, 22, 0.8)', // Yellow for 46-85 days
          'rgba(180, 35, 24, 0.8)'   // Red for 90+ days
        ],
        borderColor: [
          'rgb(6, 118, 71)',
          'rgb(243, 229, 22)',
          'rgb(180, 35, 24)'
        ],
        borderWidth: 2
      }]
    };
  }

  applyFilters(): void {
    if (!this.ready) return;
    this.loadVisualization();
  }

  clearFilters(): void {
    this.yearFilter = '';
    this.monthFilter = '';
    this.quarterFilter = '';
    this.datePreset = 'none';
    this.applyFilters();
  }

  applyDatePreset(preset: 'none' | 'thisYear' | 'lastYear' | 'thisQuarter' | 'lastQuarter' | 'thisMonth' | 'lastMonth'): void {
    if (!this.ready) return;
    this.datePreset = preset;
    const today = new Date();
    
    switch (preset) {
      case 'thisYear':
        this.yearFilter = today.getFullYear().toString();
        this.monthFilter = '';
        this.quarterFilter = '';
        break;
      case 'lastYear':
        this.yearFilter = (today.getFullYear() - 1).toString();
        this.monthFilter = '';
        this.quarterFilter = '';
        break;
      case 'thisQuarter':
        this.yearFilter = today.getFullYear().toString();
        this.quarterFilter = Math.floor(today.getMonth() / 3 + 1).toString();
        this.monthFilter = '';
        break;
      case 'lastQuarter':
        this.yearFilter = today.getFullYear().toString();
        const lastQuarter = Math.floor(today.getMonth() / 3);
        this.quarterFilter = (lastQuarter === 0 ? 4 : lastQuarter).toString();
        if (lastQuarter === 0) {
          this.yearFilter = (today.getFullYear() - 1).toString();
        }
        this.monthFilter = '';
        break;
      case 'thisMonth':
        this.yearFilter = today.getFullYear().toString();
        this.monthFilter = (today.getMonth() + 1).toString();
        this.quarterFilter = '';
        break;
      case 'lastMonth':
        const lastMonth = new Date(today.getFullYear(), today.getMonth() - 1, 1);
        this.yearFilter = lastMonth.getFullYear().toString();
        this.monthFilter = (lastMonth.getMonth() + 1).toString();
        this.quarterFilter = '';
        break;
      case 'none':
        this.yearFilter = '';
        this.monthFilter = '';
        this.quarterFilter = '';
        break;
    }
    this.applyFilters();
  }

  getCurrentYear(): number {
    return new Date().getFullYear();
  }

  getYears(): number[] {
    const currentYear = this.getCurrentYear();
    const years: number[] = [];
    for (let i = currentYear; i >= currentYear - 5; i--) {
      years.push(i);
    }
    return years;
  }

  getMonths(): { value: number; label: string }[] {
    return [
      { value: 1, label: 'January' },
      { value: 2, label: 'February' },
      { value: 3, label: 'March' },
      { value: 4, label: 'April' },
      { value: 5, label: 'May' },
      { value: 6, label: 'June' },
      { value: 7, label: 'July' },
      { value: 8, label: 'August' },
      { value: 9, label: 'September' },
      { value: 10, label: 'October' },
      { value: 11, label: 'November' },
      { value: 12, label: 'December' }
    ];
  }

  formatCurrency(value: number): string {
    if (value >= 10000000) {
      return (value / 10000000).toFixed(2) + 'Cr';
    } else if (value >= 100000) {
      return (value / 100000).toFixed(2) + 'L';
    } else if (value >= 1000) {
      return (value / 1000).toFixed(2) + 'K';
    }
    return value.toFixed(2);
  }

  formatCurrencyFull(value: number): string {
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      maximumFractionDigits: 0
    }).format(value);
  }

  truncateText(text: string, maxLength: number): string {
    if (text.length <= maxLength) return text;
    return text.substring(0, maxLength - 3) + '...';
  }

  getPresets(): Array<'none' | 'thisMonth' | 'lastMonth' | 'thisQuarter' | 'lastQuarter' | 'thisYear' | 'lastYear'> {
    return ['none', 'thisMonth', 'lastMonth', 'thisQuarter', 'lastQuarter', 'thisYear', 'lastYear'];
  }

  getPresetLabel(preset: string): string {
    switch (preset) {
      case 'none': return 'All Time';
      case 'thisMonth': return 'This Month';
      case 'lastMonth': return 'Last Month';
      case 'thisQuarter': return 'This Quarter';
      case 'lastQuarter': return 'Last Quarter';
      case 'thisYear': return 'This Year';
      case 'lastYear': return 'Last Year';
      default: return preset;
    }
  }

  get activeFilterCount(): number {
    let count = 0;
    if (this.yearFilter) count++;
    if (this.monthFilter) count++;
    if (this.quarterFilter) count++;
    return count;
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }

  ngOnDestroy(): void {
    // Cleanup if needed
  }
}

