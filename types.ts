
export interface Parent {
  id: string;
  name: string;
  color: string;
  textColor: string;
}

export interface CalendarEvent {
  date: string; // YYYY-MM-DD
  parentId: string;
  note?: string;
}
