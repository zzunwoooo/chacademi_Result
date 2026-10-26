예시 스토리 파일

ch1_ashen_night/           컷신 예시
  → 클라  .minecraft/config/chaca_cutscene/ch1_ashen_night/
  (서버에는 넣을 파일이 없다. 끝난 뒤 실행할 명령은 플러그인 config.yml 의 on-finish)

dialogue/ch1_wakeup/       대화 예시 (대화 편집기가 내보내는 zip 과 같은 모양)
  → 클라  .minecraft/config/chaca_dialogue/ch1_wakeup/          (dialogue.yml + png)
  → 서버  plugins/ChacademyStory/dialogues/ch1_wakeup/          (dialogue.yml + server_commands.yml, png 는 없어도 됨)

같은 폴더를 클라와 서버 양쪽에 그대로 복사하면 된다. 서버는 dialogue.yml 로 대화 흐름(선택지·조건·결말)을
검증하고 server_commands.yml 의 명령어·호감도를 실행한다. 두 곳의 dialogue.yml 이 다르면 서버가 클라의 진행을
인정하지 않으므로 항상 같은 파일을 넣을 것. 넣은 뒤 서버에서 /cutscene reload.
