{{- define "kl.name" -}}{{ .Release.Name }}-kl{{- end -}}
{{- define "kl.image" -}}{{ .Values.image.repository }}:{{ .Values.image.tag }}{{- end -}}
{{- define "kl.configVolume" -}}
- name: config
  configMap:
    name: {{ include "kl.name" . }}-config
{{- end -}}
{{- define "kl.configMount" -}}
- name: config
  mountPath: /etc/kl
  readOnly: true
{{- end -}}
{{- define "kl.env" -}}
- name: KL_CONFIG
  value: /etc/kl/kl.yaml
- name: HF_HUB_OFFLINE
  value: "1"
- name: KL_JAVA_OPTS
  value: {{ .Values.javaOpts | quote }}
{{- end -}}
