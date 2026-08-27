import { Component, Input, ViewEncapsulation } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BURNING_ICE_MARK_PATHS, BURNING_ICE_MARK_VIEWBOX } from './burning-ice-mark.path';

@Component({
  selector: 'app-page-state',
  standalone: true,
  imports: [CommonModule],
  encapsulation: ViewEncapsulation.None,
  template: `
    <div class="loading-state" *ngIf="kind === 'loading'" role="status" aria-live="polite">
      <div class="app-loader" aria-hidden="true">
        <div class="bi-loader-stage">
          <div class="bi-loader-bob">
            <div class="bi-loader-tilt">
              <div class="bi-loader-3d">
                <div
                  *ngFor="let i of layers"
                  class="bi-loader-layer"
                  [style.transform]="layerTransform(i)"
                >
                  <svg [attr.viewBox]="viewBox" xmlns="http://www.w3.org/2000/svg">
                    <path
                      *ngFor="let p of markPaths"
                      [attr.d]="p.d"
                      [attr.fill-rule]="p.fillRule || null"
                      fill="#111111"
                    ></path>
                  </svg>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <p class="app-loader-message">{{ message || 'Loading...' }}</p>
    </div>
    <div class="error-state" *ngIf="kind === 'error'" role="alert">
      <div class="state-icon" aria-hidden="true">⚠️</div>
      <p>{{ message }}</p>
    </div>
    <div class="empty-state" *ngIf="kind === 'empty'">
      <div class="state-icon" aria-hidden="true">📭</div>
      <p>{{ message }}</p>
    </div>
  `,
  styles: [`
    .app-loader {
      display: flex;
      align-items: center;
      justify-content: center;
      margin: 0 auto 14px;
      width: 140px;
      height: 140px;
      background: transparent;
    }

    .bi-loader-stage {
      width: 140px;
      height: 140px;
      perspective: 700px;
      perspective-origin: 50% 50%;
    }

    .bi-loader-bob {
      width: 100%;
      height: 100%;
      animation: bi-loader-bob 2.4s ease-in-out infinite;
    }

    .bi-loader-tilt {
      width: 100%;
      height: 100%;
      transform: rotateX(18deg);
      transform-style: preserve-3d;
      -webkit-transform-style: preserve-3d;
    }

    .bi-loader-3d {
      position: relative;
      width: 100%;
      height: 100%;
      transform-origin: 50% 50%;
      transform-style: preserve-3d;
      -webkit-transform-style: preserve-3d;
      animation: bi-loader-spin 2.4s linear infinite;
      -webkit-animation: bi-loader-spin 2.4s linear infinite;
    }

    .bi-loader-layer {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
    }

    .bi-loader-layer svg {
      width: 100%;
      height: 100%;
      display: block;
      overflow: visible;
    }

    @keyframes bi-loader-spin {
      from { transform: rotateY(0deg); }
      to   { transform: rotateY(360deg); }
    }

    @-webkit-keyframes bi-loader-spin {
      from { -webkit-transform: rotateY(0deg); transform: rotateY(0deg); }
      to   { -webkit-transform: rotateY(360deg); transform: rotateY(360deg); }
    }

    @keyframes bi-loader-bob {
      0%, 100% { transform: translateY(0); }
      50%      { transform: translateY(-6px); }
    }
  `]
})
export class PageStateComponent {
  @Input() kind: 'loading' | 'error' | 'empty' = 'loading';
  @Input() message = '';
  readonly markPaths = BURNING_ICE_MARK_PATHS;
  readonly viewBox = BURNING_ICE_MARK_VIEWBOX;
  readonly layers = [0, 1, 2, 3];

  layerTransform(i: number): string {
    return `translateZ(${-i * 1.2}px)`;
  }
}
