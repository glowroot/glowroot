{{/*
Expand the name of the chart.
*/}}
{{- define "glowroot-central.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "glowroot-central.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "glowroot-central.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "glowroot-central.labels" -}}
helm.sh/chart: {{ include "glowroot-central.chart" . }}
{{ include "glowroot-central.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "glowroot-central.selectorLabels" -}}
app.kubernetes.io/name: {{ include "glowroot-central.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "glowroot-central.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "glowroot-central.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "glowroot-central.secretName" -}}
{{- if .Values.cassandra.existingSecret }}
{{- .Values.cassandra.existingSecret }}
{{- else }}
{{- printf "%s-cassandra" (include "glowroot-central.fullname" .) }}
{{- end }}
{{- end }}
