import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { Api, Settings, errorKey } from '../core/api.service';

@Component({
  imports: [ReactiveFormsModule, TranslatePipe],
  template: ` <section class="card">
    <h1>{{ 'settings' | translate }}</h1>
    @if (loading()) {
      <p role="status">{{ 'loading' | translate }}</p>
    }
    @if (loaded()) {
      @if (!api.admin()) {
        <p>{{ 'readOnly' | translate }}</p>
      }
      <form [formGroup]="form" (ngSubmit)="save()">
        <label>{{ 'name' | translate }}<input formControlName="name" maxlength="120" /></label>
        <label
          >{{ 'language' | translate
          }}<select [attr.aria-label]="'language' | translate" formControlName="locale">
            <option value="fr-SN">{{ 'french' | translate }}</option>
            <option value="en">{{ 'english' | translate }}</option>
          </select></label
        >
        <label
          >{{ 'timezone' | translate }}<input formControlName="timezone" maxlength="80"
        /></label>
        <label
          >{{ 'currency' | translate }}<input formControlName="currency" maxlength="3"
        /></label>
        <label
          >{{ 'precision' | translate
          }}<input type="number" formControlName="fractionDigits" min="0" max="6"
        /></label>
        @if (api.admin()) {
          <button [disabled]="busy()">{{ 'save' | translate }}</button>
        }
      </form>
    }
    @if (message()) {
      <p role="status">{{ message() | translate }}</p>
    }
    @if (error()) {
      <p class="error" role="alert">{{ error() | translate }}</p>
    }
  </section>`,
})
export class SettingsPage {
  readonly api = inject(Api);
  readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    locale: ['fr-SN', Validators.required],
    timezone: ['Africa/Dakar', Validators.required],
    currency: ['XOF', [Validators.required, Validators.pattern('[A-Z]{3}')]],
    fractionDigits: [0, [Validators.required, Validators.min(0), Validators.max(6)]],
  });
  readonly loading = signal(true);
  readonly loaded = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  private version = 0;
  private readonly destroy = inject(DestroyRef);
  constructor() {
    this.api
      .get<Settings>('/clinic-settings')
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (settings) => {
          this.form.patchValue(settings);
          this.version = settings.version;
          this.loaded.set(true);
          if (!this.api.admin()) this.form.disable();
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }
  save() {
    if (this.form.invalid) {
      this.error.set('validation');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<Settings>('PATCH', '/clinic-settings', this.form.getRawValue(), this.version)
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (result) => {
          this.version = result.version;
          this.form.markAsPristine();
          this.message.set('saved');
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }
}
