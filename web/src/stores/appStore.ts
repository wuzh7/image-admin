import { create } from 'zustand';

export type SideTheme = 'light' | 'realDark';

export interface LayoutSettingsValue {
  sideTheme: SideTheme;
  darkMode: boolean;
  theme: string;
  radiusBase: number;
  fixedHeader: boolean;
  dynamicTitle: boolean;
  fullHeightTable: boolean;
}

export const tagsStorageKey = 'tags-view-visited-react';

export const defaultLayoutSettings: LayoutSettingsValue = {
  sideTheme: 'light',
  darkMode: false,
  theme: '#409EFF',
  radiusBase: 6,
  fixedHeader: true,
  dynamicTitle: true,
  fullHeightTable: true
};

interface AppState {
  layoutSettings: LayoutSettingsValue;
}

export const useAppStore = create<AppState>(() => ({
  layoutSettings: defaultLayoutSettings
}));
