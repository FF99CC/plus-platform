export type ProjectStage =
  | 'DRAFT'
  | 'REGISTRATION_REVIEW'
  | 'REGISTERED'
  | 'BID_REVIEW'
  | 'BID_PREPARATION'
  | 'BID_WON'
  | 'BID_LOST'
  | 'INITIATION_REVIEW'
  | 'ESTABLISHED'
  | 'TERMINATED';
export type ApplicationType = 'REGISTRATION' | 'BID' | 'INITIATION';
export type ApplicationStatus = 'SUBMITTED' | 'APPROVED' | 'RETURNED';
export type TaskKind = 'BID_DOCUMENT' | 'CERTIFICATE' | 'SEAL' | 'GUARANTEE' | 'OTHER';
export interface ProjectForm {
  name: string;
  customerName: string;
  location: string;
  acquisitionMode: 'DIRECT' | 'TENDER';
  contactName?: string;
  contactPhone?: string;
  projectType?: string;
  estimatedAmount?: string | number;
}
export interface Project extends ProjectForm {
  id: string;
  ownerId: string;
  ownerName: string;
  stage: ProjectStage;
  createdAt: string;
  updatedAt: string;
  bidName?: string;
  bidType?: string;
  bidDeadline?: string;
  openingDate?: string;
  bidAmount?: string | number;
  bidResult?: 'WON' | 'LOST';
  bidRemark?: string;
  managerName?: string;
  projectAmount?: string | number;
  plannedStart?: string;
  plannedEnd?: string;
  terminationReason?: string;
}
export interface ProjectApplication {
  id: string;
  projectId: string;
  projectName: string;
  type: ApplicationType;
  status: ApplicationStatus;
  submittedBy: string;
  submittedAt: string;
  finishedAt?: string;
  comment?: string;
  snapshot: Record<string, unknown>;
  canHandle: boolean;
  currentTasks: { taskId: string; nodeName: string; canHandle: boolean }[];
}
export interface ProjectTask {
  id: string;
  title: string;
  kind: TaskKind;
  assigneeName?: string;
  dueDate?: string;
  status: 'TODO' | 'DONE';
  note?: string;
}
export interface ProjectDetail extends Project {
  applications: ProjectApplication[];
  events: { id: string; type: string; message: string; actorName: string; createdAt: string }[];
  tasks: ProjectTask[];
  canEdit: boolean;
  canSubmit: boolean;
}
export interface ProjectQuery {
  pageNum: number;
  pageSize: number;
  search?: string;
  stage?: ProjectStage | '';
}
export interface PageData<T> {
  rows: T[];
  total: number;
}
export interface BidForm {
  bidName: string;
  bidType: string;
  bidDeadline: string;
}
export interface InitiationForm {
  managerName: string;
  projectAmount: string | number;
  plannedStart: string;
  plannedEnd: string;
}
export interface OpeningForm {
  bidResult: 'WON' | 'LOST';
  openingDate: string;
  bidAmount?: string | number;
  bidRemark?: string;
}
export interface PreparationForm {
  title: string;
  kind: TaskKind;
  assigneeName?: string;
  dueDate?: string;
  note?: string;
}
