#!/bin/bash

if [[ $(uname) == "Darwin" ]]; then
        THIS_SCRIPT=$(python -c 'import os,sys; print os.path.realpath(sys.argv[1])' "$0")
        TCNATIVE_SUFFIX="jnilib"
else
        THIS_SCRIPT=$(readlink -f "$0")
        TCNATIVE_SUFFIX="so"
fi

if [ -z "$1" ]; then
  export INSTANCE="1"
else
  export INSTANCE="$1"
fi

PID=$(jps -m | grep -E 'timely-balancer-.*-exec.jar' | grep "instance=${INSTANCE}" | awk '{print $1}')

if [ "$PID" == "" ]; then

    THIS_DIR="${THIS_SCRIPT%/*}"
    BASE_DIR=$(cd "$THIS_DIR"/.. && pwd)
    TMP_DIR="${BASE_DIR}/tmp"
    LIB_DIR="${BASE_DIR}/lib"
    BIN_DIR="${BASE_DIR}/bin"
    export CONF_DIR="${BASE_DIR}/conf"
    NATIVE_DIR="${BIN_DIR}/META-INF/native"

    set -a
    . "${BIN_DIR}/timely-balancer-env.sh"
    set +a

    # use either a value from timely-balancer-env.sh or the default
    export LOG_DIR="${TIMELY_LOG_DIR:-${BASE_DIR}/logs}"

    if [[ -e ${TMP_DIR} ]]; then
      rm -rf "${TMP_DIR}"
    fi
    mkdir "${TMP_DIR}"

    if [[ -e ${NATIVE_DIR} ]]; then
      rm -rf "${NATIVE_DIR}"
    fi
    mkdir -p "${NATIVE_DIR}"

    if [[ ! -e ${LOG_DIR} ]]; then
      mkdir "${LOG_DIR}"
    fi

    pushd "${BASE_DIR}/bin" || exit
    "${JAVA_HOME}/bin/jar xf ${LIB_DIR}/netty-tcnative-boringssl-static*.jar META-INF/native/libnetty_tcnative_linux_x86_64.${TCNATIVE_SUFFIX}"
    "${JAVA_HOME}/bin/jar xf ${LIB_DIR}/netty-all*.jar META-INF/native/libnetty_transport_native_epoll_x86_64.${TCNATIVE_SUFFIX}"
    popd || exit

    JVM_ARGS="-Xmx2G -Xms2G -XX:NewSize=1G -XX:MaxNewSize=1G"
    JVM_ARGS="${JVM_ARGS} -Djava.library.path=${NATIVE_DIR}"
    JVM_ARGS="${JVM_ARGS} -Dlogging.config=${CONF_DIR}/log4j2.yml"
    JVM_ARGS="${JVM_ARGS} -XX:+UseG1GC -XX:+UseStringDeduplication"
    JVM_ARGS="${JVM_ARGS} -Djava.net.preferIPv4Stack=true"
    JVM_ARGS="${JVM_ARGS} -XX:+UseNUMA"

    echo "${JAVA_HOME}/bin/java ${JVM_ARGS} -jar ${THIS_DIR}/timely-balancer-*-exec.jar --instance=${INSTANCE}"
    nohup "${JAVA_HOME}"/bin/java ${JVM_ARGS} -jar "${THIS_DIR}"/timely-balancer-*-exec.jar --instance="${INSTANCE}" >> "${LOG_DIR}/timely-balancer${INSTANCE}.out" 2>&1 &
else
    echo "timely-balancer --instance=${INSTANCE} already running with pid ${PID}"
fi
