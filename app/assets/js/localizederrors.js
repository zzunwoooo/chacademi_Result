const messages = {
    'Duplicate BC7 system limit': 'BC7 시스템 제한 설정이 중복되었습니다',
    'Linked camera compatibility path is prohibited': '카메라 호환 설정 경로에 연결 파일을 사용할 수 없습니다',
    'Camera mod backup hash mismatch': '카메라 모드 백업의 해시가 일치하지 않습니다',
    'Linked client asset target is prohibited': '클라이언트 리소스 대상에 연결 파일을 사용할 수 없습니다',
    'Required client asset bundle is missing': '필수 클라이언트 리소스 묶음이 없습니다',
    'Linked instance root is prohibited': '인스턴스 루트에 연결 폴더를 사용할 수 없습니다',
    'Invalid client asset archive contract': '클라이언트 리소스 압축 파일의 배포 정보가 올바르지 않습니다',
    'Client asset archive hash mismatch': '클라이언트 리소스 압축 파일의 해시가 일치하지 않습니다',
    'Invalid client asset manifest': '클라이언트 리소스 목록이 올바르지 않습니다',
    'Unsafe client asset manifest entry': '클라이언트 리소스 목록에 안전하지 않은 항목이 있습니다',
    'Client asset archive exceeds limit': '클라이언트 리소스 압축 파일이 크기 제한을 초과했습니다',
    'Unexpected client asset archive entry': '클라이언트 리소스 압축 파일에 예상하지 못한 항목이 있습니다',
    'Client asset member hash mismatch': '클라이언트 리소스 파일의 해시가 일치하지 않습니다',
    'Client asset target must be a file': '클라이언트 리소스 대상은 일반 파일이어야 합니다',
    'Invalid game session marker': '실행 중인 게임의 세션 정보가 올바르지 않습니다',
    'Unsafe integrated content path': '통합 콘텐츠 경로가 안전하지 않습니다',
    'Linked integrated content is prohibited': '통합 콘텐츠에 연결 파일을 사용할 수 없습니다',
    'Missing integrated pack contract': '통합 팩의 배포 정보가 없습니다',
    'Integrated pack hash mismatch': '통합 팩의 해시가 일치하지 않습니다',
    'Duplicate resource pack option': '리소스 팩 옵션이 중복되었습니다',
    'Invalid resource pack option': '리소스 팩 옵션이 올바르지 않습니다',
    'Unsafe retirement entry': '이전 콘텐츠 정리 목록에 안전하지 않은 항목이 있습니다',
    'options.txt must be an ordinary file': 'options.txt는 연결 파일이 아닌 일반 파일이어야 합니다',
    'Pet model bundle path or SHA256 is invalid': '펫 모델 묶음의 경로 또는 SHA256이 올바르지 않습니다',
    'Pet model directory cannot be a symbolic link': '펫 모델 폴더에 심볼릭 링크를 사용할 수 없습니다',
    'Pet model bundle SHA256 mismatch': '펫 모델 묶음의 SHA256이 일치하지 않습니다',
    'Pet model bundle manifest is missing': '펫 모델 묶음의 목록 파일이 없습니다',
    'Pet model bundle has no models': '펫 모델 묶음에 모델이 없습니다',
    'Pet model manifest entry is invalid': '펫 모델 목록의 항목이 올바르지 않습니다',
    'Duplicate pet model destination': '펫 모델 저장 경로가 중복되었습니다',
    'Unexpected file in pet model bundle': '펫 모델 묶음에 예상하지 못한 파일이 있습니다',
    'Pet model bundle entry is missing': '펫 모델 묶음에 필요한 항목이 없습니다',
    'Pet model file hash mismatch': '펫 모델 파일의 해시가 일치하지 않습니다',
    'Pet model target must be an ordinary file': '펫 모델 대상은 연결 파일이 아닌 일반 파일이어야 합니다',
    'Invalid servers.dat NBT': 'servers.dat의 NBT 데이터가 올바르지 않습니다',
    'NBT nesting limit': 'NBT 데이터의 중첩 깊이 제한을 초과했습니다',
    'Negative NBT array': 'NBT 배열 길이가 음수입니다',
    'Invalid NBT list': 'NBT 목록이 올바르지 않습니다',
    'Unsupported NBT tag': '지원하지 않는 NBT 태그입니다',
    'Unexpected Chacademi connection address': '차카데미 접속 주소가 승인된 주소와 일치하지 않습니다',
    'servers.dat must be an ordinary file': 'servers.dat는 연결 파일이 아닌 일반 파일이어야 합니다',
    'servers.dat too large': 'servers.dat 파일이 크기 제한을 초과했습니다',
    'Invalid root servers.dat': 'servers.dat의 루트 데이터가 올바르지 않습니다',
    'Invalid servers list': '서버 목록이 올바르지 않습니다',
    'Invalid server list type': '서버 목록의 데이터 형식이 올바르지 않습니다',
    'Invalid server count': '서버 수가 허용 범위를 벗어났습니다',
    'Invalid server list boundary': '서버 목록의 데이터 범위가 올바르지 않습니다',
    'Unsafe seed configuration module': '기본 설정 모듈이 안전하지 않습니다',
    'Seed path outside instance': '기본 설정 경로가 인스턴스 폴더 밖을 가리킵니다',
    'Seed target must be an ordinary file': '기본 설정 대상은 연결 파일이 아닌 일반 파일이어야 합니다',
    'Unsafe shader defaults path': '셰이더 기본 설정 경로가 안전하지 않습니다',
    'Linked shader defaults path is prohibited': '셰이더 기본 설정 경로에 연결 파일을 사용할 수 없습니다',
    'Invalid shader defaults policy module': '셰이더 기본 설정 정책 모듈이 올바르지 않습니다',
    'Shader defaults policy hash mismatch': '셰이더 기본 설정 정책의 해시가 일치하지 않습니다',
    'Invalid shader defaults contract': '셰이더 기본 설정의 배포 정보가 올바르지 않습니다',
    'Invalid shader preset property': '셰이더 프리셋 속성이 올바르지 않습니다',
    'Unexpected shader preset key count': '셰이더 프리셋의 설정 항목 수가 올바르지 않습니다',
    'Invalid shader defaults state': '셰이더 기본 설정 상태가 올바르지 않습니다',
    'Shader ZIP hash mismatch': '셰이더 ZIP 파일의 해시가 일치하지 않습니다',
    'Shader ZIP is incomplete': '셰이더 ZIP 파일에 필요한 항목이 없습니다',
    'Shader configuration must be a file': '셰이더 설정은 일반 파일이어야 합니다',
    'Invalid VFX catalogue limits': '시각 효과 목록의 제한 설정이 올바르지 않습니다',
    'Error during request.': '요청 처리 중 오류가 발생했습니다.',
    'Request received unexepected body (Parse Error).': '서버 응답을 해석할 수 없습니다. (데이터 형식 오류)'
}

const escape = value => String(value).replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', '\'': '&#39;'}[c]))

exports.describe = function(error, fallback){
    const message = typeof error === 'string' ? error : error && (error.displayable || error.message)
    if(!message){ return fallback }
    if(messages[message]){ return messages[message] }
    if(/[가-힣]/.test(message)){ return escape(message) }
    for(const [prefix, label] of Object.entries({'Duplicate VFX limit: ': '시각 효과 제한 설정 중복: ', 'Invalid VFX limit: ': '올바르지 않은 시각 효과 제한 설정: '})){
        if(message.startsWith(prefix)){ return label + escape(message.slice(prefix.length)) }
    }
    // Keep unfamiliar platform/network diagnostics available without presenting them as instructions.
    return fallback + '<br><br>진단 정보: <code>' + escape(message) + '</code>'
}
