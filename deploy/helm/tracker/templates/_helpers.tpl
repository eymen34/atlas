{{/* Chart name (overridable via nameOverride), truncated to the 63-char DNS limit. */}}
{{- define "tracker.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully-qualified app name. fullnameOverride wins; otherwise <release>-<name>, collapsing
to just <release> when the release name already contains the chart name (so
`helm install tracker ./tracker` yields `tracker`, not `tracker-tracker`).
*/}}
{{- define "tracker.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "tracker.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "tracker.labels" -}}
helm.sh/chart: {{ include "tracker.chart" . }}
{{ include "tracker.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "tracker.selectorLabels" -}}
app.kubernetes.io/name: {{ include "tracker.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
The Secret name referenced by BOTH the Deployment (envFrom secretRef) and the drain
CronJob (secretKeyRef). When existingSecret is set the chart creates NO Secret and
references the operator's; otherwise it references the chart-created Secret.
*/}}
{{- define "tracker.secretName" -}}
{{- if .Values.existingSecret -}}
{{- .Values.existingSecret -}}
{{- else -}}
{{- include "tracker.fullname" . -}}
{{- end -}}
{{- end -}}

{{/* In-cluster Service DNS for the app (used by the drain CronJob — NOT APP_BASE_URL). */}}
{{- define "tracker.serviceDns" -}}
{{- printf "http://%s.%s.svc.cluster.local:%d" (include "tracker.fullname" .) .Release.Namespace (int .Values.service.port) -}}
{{- end -}}
