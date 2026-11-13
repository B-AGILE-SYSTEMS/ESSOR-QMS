import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Limits, SpcAlert, SpcPoint, chartGeometry, flaggedPoints, fmt } from '../../core/m3b.models';

interface Line { y: number; label: string; cls: string }

/** Carte de contrôle SVG (X̄ ou R) : limites LCL/CL/UCL, tolérances, points hors limites en rouge. */
@Component({
  selector: 'eq-spc-chart',
  template: `
    @if (geo(); as g) {
      <svg [attr.viewBox]="'0 0 ' + g.width + ' ' + g.height" role="img" [attr.aria-label]="title()">
        <title>{{ title() }}</title>
        @for (l of lines(); track l.label) {
          <line [attr.x1]="g.pad.l" [attr.x2]="g.width - g.pad.r" [attr.y1]="l.y" [attr.y2]="l.y" [attr.class]="l.cls" />
          <text [attr.x]="g.width - g.pad.r + 6" [attr.y]="l.y + 4" [attr.class]="'lb ' + l.cls">{{ l.label }}</text>
        }
        <polyline class="path" [attr.points]="path()" />
        @for (p of dots(); track p.i) {
          <circle [attr.cx]="p.x" [attr.cy]="p.y" [attr.r]="p.bad ? 6 : 4" [attr.class]="p.bad ? 'dot bad' : 'dot'">
            <title>Sous-groupe {{ p.n }} : {{ p.v }}</title></circle>
        }
        <text class="ax" [attr.x]="g.pad.l - 8" [attr.y]="g.pad.t + 10" text-anchor="end">{{ fmtN(g.max) }}</text>
        <text class="ax" [attr.x]="g.pad.l - 8" [attr.y]="g.height - g.pad.b" text-anchor="end">{{ fmtN(g.min) }}</text>
      </svg>
      <p class="note">{{ flagged().size }} point(s) hors limites ou en alerte.</p>
    }
  `,
  styles: `
    svg { width: 100%; height: auto; display: block; background: var(--surf); }
    line { stroke-width: 1.5; } .ucl, .lcl { stroke: var(--crit); stroke-dasharray: 6 4; } .cl { stroke: var(--ink2); }
    .tol { stroke: var(--warn); stroke-dasharray: 2 4; }
    .lb { font: 600 11px var(--mono); fill: var(--ink2); } .lb.ucl, .lb.lcl { fill: var(--crit); } .lb.tol { fill: var(--warn); }
    .ax { font: 11px var(--mono); fill: var(--ink2); }
    .path { fill: none; stroke: var(--info); stroke-width: 2; }
    .dot { fill: var(--info); } .dot.bad { fill: var(--crit); stroke: var(--surf); stroke-width: 2; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SpcChartComponent {
  readonly title = input('Carte de contrôle');
  readonly points = input<SpcPoint[]>([]);
  readonly limits = input<Limits | null>(null);
  readonly alerts = input<SpcAlert[]>([]);
  readonly kind = input<'mean' | 'range'>('mean');
  readonly tolerances = input<{ min: number | null; max: number | null } | null>(null);
  protected readonly fmtN = (n: number) => fmt(n);

  private readonly values = computed(() => this.points().map((p) => (this.kind() === 'mean' ? p.mean : p.range)));
  protected readonly geo = computed(() => {
    const l = this.limits();
    const t = this.tolerances();
    const ref = [l?.lcl, l?.cl, l?.ucl, t?.min, t?.max].filter((v): v is number => typeof v === 'number');
    return chartGeometry(this.values(), ref);
  });
  protected readonly flagged = computed(() =>
    flaggedPoints(this.points(), this.limits(), this.kind() === 'mean' ? this.alerts() : [], (p) => (this.kind() === 'mean' ? p.mean : p.range)));
  protected readonly lines = computed<Line[]>(() => {
    const g = this.geo();
    const l = this.limits();
    const t = this.tolerances();
    const out: Line[] = [];
    if (l) out.push({ y: g.y(l.ucl), label: `UCL ${fmt(l.ucl)}`, cls: 'ucl' }, { y: g.y(l.cl), label: `CL ${fmt(l.cl)}`, cls: 'cl' },
      { y: g.y(l.lcl), label: `LCL ${fmt(l.lcl)}`, cls: 'lcl' });
    if (t?.min !== null && t?.min !== undefined) out.push({ y: g.y(t.min), label: `Tol− ${fmt(t.min)}`, cls: 'tol' });
    if (t?.max !== null && t?.max !== undefined) out.push({ y: g.y(t.max), label: `Tol+ ${fmt(t.max)}`, cls: 'tol' });
    return out;
  });
  protected readonly dots = computed(() => {
    const g = this.geo();
    const bad = this.flagged();
    return this.values().map((v, i) => ({ i, n: this.points()[i].index, x: g.x(i), y: g.y(v), v: fmt(v), bad: bad.has(i) }));
  });
  protected readonly path = computed(() => this.dots().map((d) => `${d.x},${d.y}`).join(' '));
}
